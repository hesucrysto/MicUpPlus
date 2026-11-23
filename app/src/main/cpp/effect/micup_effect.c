/*
 * MicUp++ system capture effect.
 *
 * Loaded by audioserver through /vendor/etc/audio_effects.xml as a preprocess
 * effect. It sits on the capture path, so every app that opens a microphone
 * (Discord uses AUDIO_SOURCE_VOICE_COMMUNICATION) records the processed voice.
 *
 * Real-time rules observed here:
 *   - no malloc/free after create_effect()
 *   - no locks
 *   - the only syscall in the fast path is the rate-limited parameter poll
 *     (every 32 blocks, ~6 times a second at 5 ms blocks)
 */

#include "audio_effect.h"

#include <errno.h>
#include <fcntl.h>
#include <math.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include <android/log.h>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

#define LOG_TAG "MicUpEffect"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define FX_MAX_FRAMES 4096
#define FX_MAX_CH     2

#define FX_RING      16384 /* power of two */
#define FX_MAKE_RING 4096

#define PARAM_PATH    "/data/local/tmp/micup_params.bin"
#define PARAM_MAGIC   0x4D555050u /* "MUPP" */
#define PARAM_VERSION 1u
#define PARAM_BYTES   64

/* ─── Parameter block, mirrored byte-for-byte in Kotlin ─────────────────────── */
typedef struct {
    uint32_t magic;
    uint32_t version;
    uint32_t enabled;
    uint32_t preset;
    float    pitch;      /* semitones, -24..24 */
    float    body;       /* formant-ish tilt, -12..12 */
    float    lowDb;
    float    midDb;
    float    highDb;
    float    drive;      /* 0..1 */
    float    mix;        /* 0..1 */
    float    gateDb;     /* -80..0 */
    float    compDb;     /* -60..0 */
    float    compRatio;  /* 1..20 */
    float    reverb;     /* 0..1 */
    float    inputGainDb;
} fx_params_t;

typedef struct {
    float b0, b1, b2, a1, a2;
    float x1, x2, y1, y2;
} biquad_t;

typedef struct {
    uint32_t size;
    uint32_t idx;
    float    buf[FX_MAKE_RING];
    float    damp;
} delayline_t;

typedef struct {
    float readPos;
    int   elapsed;
    int   grain;
} grain_tap_t;

typedef struct {
    /* EffectControlInterface handle: must stay first, the framework passes
     * this pointer back as effect_handle_t. */
    const struct effect_interface_s* itfe;

    fx_params_t p;
    volatile int enabled;
    int          sr;
    int          channels;
    int          format;
    uint32_t     blockCounter;

    float    dcX[FX_MAX_CH];
    float    dcY[FX_MAX_CH];
    float    gateEnv[FX_MAX_CH];
    float    gateGain[FX_MAX_CH];
    float    compEnv[FX_MAX_CH];
    biquad_t eqLow[FX_MAX_CH];
    biquad_t eqMid[FX_MAX_CH];
    biquad_t eqHigh[FX_MAX_CH];
    biquad_t bodyLo[FX_MAX_CH];
    biquad_t bodyHi[FX_MAX_CH];

    float       ring[FX_MAX_CH][FX_RING];
    uint32_t    ringW[FX_MAX_CH];
    grain_tap_t tapA[FX_MAX_CH];
    grain_tap_t tapB[FX_MAX_CH];

    delayline_t comb[4];
    delayline_t ap[2];
} fx_t;

static float clampf(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }

/* ─── Biquads (RBJ cookbook) ───────────────────────────────────────────────── */
static void bq_reset(biquad_t* b) { b->x1 = b->x2 = b->y1 = b->y2 = 0.f; }

static void bq_peaking(biquad_t* b, float sr, float f, float gainDb, float q) {
    if (f < 20.f) f = 20.f;
    if (f > sr * 0.45f) f = sr * 0.45f;
    float A = powf(10.f, gainDb / 40.f);
    float w = 2.f * (float)M_PI * f / sr;
    float alpha = sinf(w) / (2.f * q);
    float c = cosf(w);
    float a0 = 1.f + alpha / A;
    b->b0 = (1.f + alpha * A) / a0;
    b->b1 = (-2.f * c) / a0;
    b->b2 = (1.f - alpha * A) / a0;
    b->a1 = (-2.f * c) / a0;
    b->a2 = (1.f - alpha / A) / a0;
}

static void bq_lowshelf(biquad_t* b, float sr, float f, float gainDb, float q) {
    if (f < 20.f) f = 20.f;
    if (f > sr * 0.45f) f = sr * 0.45f;
    float A = powf(10.f, gainDb / 40.f);
    float w = 2.f * (float)M_PI * f / sr;
    float c = cosf(w);
    float s = sinf(w);
    float alpha = s / (2.f * q);
    float beta = 2.f * sqrtf(A) * alpha;
    float a0 = (A + 1.f) + (A - 1.f) * c + beta;
    b->b0 = A * ((A + 1.f) - (A - 1.f) * c + beta) / a0;
    b->b1 = 2.f * A * ((A - 1.f) - (A + 1.f) * c) / a0;
    b->b2 = A * ((A + 1.f) - (A - 1.f) * c - beta) / a0;
    b->a1 = -2.f * ((A - 1.f) + (A + 1.f) * c) / a0;
    b->a2 = ((A + 1.f) + (A - 1.f) * c - beta) / a0;
}

static void bq_highshelf(biquad_t* b, float sr, float f, float gainDb, float q) {
    if (f < 20.f) f = 20.f;
    if (f > sr * 0.45f) f = sr * 0.45f;
    float A = powf(10.f, gainDb / 40.f);
    float w = 2.f * (float)M_PI * f / sr;
    float c = cosf(w);
    float s = sinf(w);
    float alpha = s / (2.f * q);
    float beta = 2.f * sqrtf(A) * alpha;
    float a0 = (A + 1.f) - (A - 1.f) * c + beta;
    b->b0 = A * ((A + 1.f) + (A - 1.f) * c + beta) / a0;
    b->b1 = -2.f * A * ((A - 1.f) + (A + 1.f) * c) / a0;
    b->b2 = A * ((A + 1.f) + (A - 1.f) * c - beta) / a0;
    b->a1 = 2.f * ((A - 1.f) - (A + 1.f) * c) / a0;
    b->a2 = ((A + 1.f) - (A - 1.f) * c - beta) / a0;
}

static inline float bq_run(biquad_t* b, float x) {
    float y = b->b0 * x + b->b1 * b->x1 + b->b2 * b->x2 - b->a1 * b->y1 - b->a2 * b->y2;
    b->x2 = b->x1;
    b->x1 = x;
    b->y2 = b->y1;
    b->y1 = y;
    return y;
}

/* ─── Delay lines ─────────────────────────────────────────────────────────── */
static void dl_init(delayline_t* d, int sr, int delaySamples) {
    (void)sr;
    uint32_t s = (uint32_t)delaySamples;
    if (s >= FX_MAKE_RING) s = FX_MAKE_RING - 1;
    if (s < 32) s = 32;
    d->size = s;
    d->idx = 0;
    d->damp = 0.f;
    memset(d->buf, 0, sizeof(d->buf));
}

static inline float dl_read(const delayline_t* d) {
    uint32_t i = d->idx + d->size - 1;
    if (i >= d->size) i -= d->size;
    return d->buf[i];
}

/* ─── Parameter file ──────────────────────────────────────────────────────── */
static int fx_load_params(fx_params_t* out) {
    int fd = open(PARAM_PATH, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return -1;
    fx_params_t tmp;
    ssize_t n = read(fd, &tmp, PARAM_BYTES);
    close(fd);
    if (n != PARAM_BYTES) return -1;
    if (tmp.magic != PARAM_MAGIC || tmp.version != PARAM_VERSION) return -1;
    *out = tmp;
    return 0;
}

/* ─── Coefficients and state ──────────────────────────────────────────────── */
static void fx_apply_coeffs(fx_t* fx) {
    float sr = (float)fx->sr;
    float body = clampf(fx->p.body, -12.f, 12.f);
    float bodyRatio = powf(2.f, body / 12.f);

    for (int c = 0; c < fx->channels; c++) {
        bq_lowshelf(&fx->eqLow[c], sr, 180.f, clampf(fx->p.lowDb, -18.f, 18.f), 0.7f);
        bq_peaking(&fx->eqMid[c], sr, 1400.f, clampf(fx->p.midDb, -18.f, 18.f), 0.9f);
        bq_highshelf(&fx->eqHigh[c], sr, 4200.f, clampf(fx->p.highDb, -18.f, 18.f), 0.7f);
        bq_peaking(&fx->bodyLo[c], sr, 480.f * bodyRatio, body * 0.55f, 1.1f);
        bq_peaking(&fx->bodyHi[c], sr, 1750.f * bodyRatio, -body * 0.25f, 1.1f);
    }

    int grain = (int)(sr / 43.f);
    if (grain < 256) grain = 256;
    if (grain > 2048) grain = 2048;
    for (int c = 0; c < fx->channels; c++) {
        fx->tapA[c].grain = grain;
        fx->tapB[c].grain = grain;
    }
}

static void fx_reset_state(fx_t* fx) {
    fx->blockCounter = 0;
    memset(fx->dcX, 0, sizeof(fx->dcX));
    memset(fx->dcY, 0, sizeof(fx->dcY));
    memset(fx->gateEnv, 0, sizeof(fx->gateEnv));
    memset(fx->compEnv, 0, sizeof(fx->compEnv));
    for (int c = 0; c < FX_MAX_CH; c++) {
        fx->gateGain[c] = 1.f;
        bq_reset(&fx->eqLow[c]);
        bq_reset(&fx->eqMid[c]);
        bq_reset(&fx->eqHigh[c]);
        bq_reset(&fx->bodyLo[c]);
        bq_reset(&fx->bodyHi[c]);
        memset(fx->ring[c], 0, sizeof(fx->ring[c]));
        fx->ringW[c] = 0;
        fx->tapA[c].elapsed = -1;
        fx->tapB[c].elapsed = -1;
        fx->tapA[c].readPos = 0.f;
        fx->tapB[c].readPos = 0.f;
    }
    for (int i = 0; i < 4; i++) dl_init(&fx->comb[i], fx->sr, 1116 + i * 70);
    dl_init(&fx->ap[0], fx->sr, 556);
    dl_init(&fx->ap[1], fx->sr, 441);
}

/* ─── DSP blocks ──────────────────────────────────────────────────────────── */
static inline float fx_gate(fx_t* fx, int c, float x) {
    if (fx->p.gateDb <= -79.f) return x;

    float ax = fabsf(x);
    float att = 1.f - expf(-1.f / (0.001f * (float)fx->sr));
    float rel = 1.f - expf(-1.f / (0.120f * (float)fx->sr));
    float coef = ax > fx->gateEnv[c] ? att : rel;
    fx->gateEnv[c] += (ax - fx->gateEnv[c]) * coef;

    float envDb = 20.f * log10f(fx->gateEnv[c] + 1e-9f);
    float tDb = clampf(fx->p.gateDb, -80.f, 0.f);
    float open = clampf((envDb - tDb) / 6.f, 0.f, 1.f);
    open = open * open * (3.f - 2.f * open);
    fx->gateGain[c] += (open - fx->gateGain[c]) * 0.002f;
    return x * fx->gateGain[c];
}

static inline float fx_comp(fx_t* fx, int c, float x) {
    float thresh = clampf(fx->p.compDb, -60.f, 0.f);
    float ratio = clampf(fx->p.compRatio, 1.f, 20.f);
    float ax = fabsf(x);
    float att = 1.f - expf(-1.f / (0.010f * (float)fx->sr));
    float rel = 1.f - expf(-1.f / (0.150f * (float)fx->sr));
    float coef = ax > fx->compEnv[c] ? att : rel;
    fx->compEnv[c] += (ax - fx->compEnv[c]) * coef;

    float over = 20.f * log10f(fx->compEnv[c] + 1e-9f) - thresh;
    float grDb = 0.f;
    if (over > 0.f) {
        grDb = over * (1.f - 1.f / ratio);
        if (over < 6.f) grDb *= over / 6.f;
    }
    return x * powf(10.f, -grDb / 20.f);
}

static inline float read_ring(const float* ring, float pos) {
    float f = pos;
    while (f < 0.f) f += (float)FX_RING;
    while (f >= (float)FX_RING) f -= (float)FX_RING;
    uint32_t i0 = (uint32_t)f;
    float frac = f - (float)i0;
    uint32_t i1 = (i0 + 1) & (uint32_t)(FX_RING - 1);
    return ring[i0] * (1.f - frac) + ring[i1] * frac;
}

static float fx_pitch(fx_t* fx, int c, float in) {
    float semis = clampf(fx->p.pitch, -24.f, 24.f);
    if (fabsf(semis) < 0.02f) {
        /* keep the delay line warm so engaging pitch does not start from silence */
        fx->ring[c][fx->ringW[c]] = in;
        fx->ringW[c] = (fx->ringW[c] + 1) & (uint32_t)(FX_RING - 1);
        fx->tapA[c].elapsed = -1;
        fx->tapB[c].elapsed = -1;
        return in;
    }

    float rate = powf(2.f, semis / 12.f);
    uint32_t w = fx->ringW[c];
    fx->ring[c][w] = in;

    grain_tap_t* ta = &fx->tapA[c];
    grain_tap_t* tb = &fx->tapB[c];
    if (ta->elapsed < 0) {
        ta->elapsed = 0;
        ta->readPos = (float)w - (float)ta->grain;
        tb->elapsed = ta->grain / 2;
        tb->readPos = (float)w - (float)ta->grain + (float)(ta->grain / 2) * rate;
    }

    float wa = 0.5f - 0.5f * cosf(2.f * (float)M_PI * (float)ta->elapsed / (float)ta->grain);
    float wb = 0.5f - 0.5f * cosf(2.f * (float)M_PI * (float)tb->elapsed / (float)tb->grain);
    float sa = read_ring(fx->ring[c], ta->readPos);
    float sb = read_ring(fx->ring[c], tb->readPos);

    float norm = wa + wb;
    float out = norm > 1e-3f ? (sa * wa + sb * wb) / norm : sa;

    ta->readPos += rate;
    tb->readPos += rate;
    ta->elapsed++;
    tb->elapsed++;
    if (ta->elapsed >= ta->grain) {
        ta->elapsed = 0;
        ta->readPos = (float)w - (float)ta->grain;
    }
    if (tb->elapsed >= tb->grain) {
        tb->elapsed = 0;
        tb->readPos = (float)w - (float)tb->grain;
    }
    while (ta->readPos >= (float)FX_RING) ta->readPos -= (float)FX_RING;
    while (tb->readPos >= (float)FX_RING) tb->readPos -= (float)FX_RING;
    while (ta->readPos < 0.f) ta->readPos += (float)FX_RING;
    while (tb->readPos < 0.f) tb->readPos += (float)FX_RING;

    fx->ringW[c] = (w + 1) & (uint32_t)(FX_RING - 1);
    return out;
}

static float fx_reverb(fx_t* fx, float in) {
    float mix = clampf(fx->p.reverb, 0.f, 1.f);
    if (mix <= 0.001f) return in;

    float wet = 0.f;
    for (int i = 0; i < 4; i++) {
        delayline_t* d = &fx->comb[i];
        float y = dl_read(d);
        d->damp = y * 0.75f + d->damp * 0.25f;
        d->buf[d->idx] = in + d->damp * 0.78f;
        d->idx = (d->idx + 1) % d->size;
        wet += y;
    }
    wet *= 0.25f;
    for (int i = 0; i < 2; i++) {
        delayline_t* d = &fx->ap[i];
        float delayed = dl_read(d);
        float y = -wet + delayed;
        d->buf[d->idx] = wet + delayed * 0.5f;
        d->idx = (d->idx + 1) % d->size;
        wet = y;
    }
    return in * (1.f - mix) + wet * mix;
}

/* ─── Effect interface ────────────────────────────────────────────────────── */
static const effect_descriptor_t g_descriptor = {
    {0x6d696375, 0x7000, 0x4d69, 0x6355, {0x70, 0x00, 0x00, 0x00, 0x00, 0x02}},
    {0x6d696375, 0x7000, 0x4d69, 0x6355, {0x70, 0x00, 0x00, 0x00, 0x00, 0x01}},
    EFFECT_CONTROL_API_VERSION,
    EFFECT_FLAG_TYPE_PRE_PROC | EFFECT_FLAG_INPUT_DIRECT | EFFECT_FLAG_OUTPUT_DIRECT,
    20,
    12,
    "MicUp++ Voice Engine",
    "MicUp++",
};

static int fx_configure(fx_t* fx, const effect_config_t* cfg) {
    const buffer_config_t* c = &cfg->inputCfg;
    int sr = (int)(c->samplingRate ? c->samplingRate : cfg->outputCfg.samplingRate);
    if (sr < 8000 || sr > 192000) return -EINVAL;

    uint32_t mask = c->channels ? c->channels : cfg->outputCfg.channels;
    int ch = 0;
    if (mask == AUDIO_CHANNEL_IN_MONO) ch = 1;
    else if (mask == AUDIO_CHANNEL_IN_STEREO) ch = 2;
    else {
        uint32_t m = mask;
        while (m) { ch += (int)(m & 1u); m >>= 1; }
        if (ch <= 0) ch = 1;
        if (ch > FX_MAX_CH) ch = FX_MAX_CH;
    }

    int fmt = (int)c->format;
    if (fmt == 0) fmt = (int)cfg->outputCfg.format;

    fx->sr = sr;
    fx->channels = ch;
    fx->format = fmt;
    fx_apply_coeffs(fx);
    fx_reset_state(fx);
    LOGI("configured: %d Hz, %d ch, fmt 0x%x", sr, ch, fmt);
    return 0;
}

static void fx_passthrough(const fx_t* fx, const audio_buffer_t* in, audio_buffer_t* out) {
    size_t bytes = in->frameCount * (size_t)fx->channels;
    if (fx->format == AUDIO_FORMAT_PCM_FLOAT) bytes *= sizeof(float);
    else if (fx->format == AUDIO_FORMAT_PCM_32_BIT) bytes *= sizeof(int32_t);
    else bytes *= sizeof(int16_t);
    if (in->raw != out->raw) memcpy(out->raw, in->raw, bytes);
}

static int32_t fx_process(effect_handle_t self, audio_buffer_t* in, audio_buffer_t* out) {
    fx_t* fx = (fx_t*)self;
    if (!fx) return -EINVAL;
    if (!in || !out || !in->raw || !out->raw) return -EINVAL;
    if (!fx->enabled || !fx->p.enabled) {
        fx_passthrough(fx, in, out);
        return 0;
    }

    if ((fx->blockCounter++ & 31u) == 0u) {
        fx_params_t np;
        if (fx_load_params(&np) == 0) {
            float oldBody = fx->p.body;
            float oldLow = fx->p.lowDb, oldMid = fx->p.midDb, oldHigh = fx->p.highDb;
            fx->p = np;
            if (np.body != oldBody || np.lowDb != oldLow || np.midDb != oldMid ||
                np.highDb != oldHigh) {
                fx_apply_coeffs(fx);
            }
        }
    }

    if (fx->format != AUDIO_FORMAT_PCM_16_BIT && fx->format != AUDIO_FORMAT_PCM_FLOAT) {
        fx_passthrough(fx, in, out);
        return 0;
    }
    if (fx->format == AUDIO_FORMAT_PCM_FLOAT && in->f32 == NULL) return -EINVAL;

    size_t frames = in->frameCount;
    if (frames == 0) return 0;
    size_t processed = frames > FX_MAX_FRAMES ? FX_MAX_FRAMES : frames;

    int ch = fx->channels;
    float inGain = powf(10.f, clampf(fx->p.inputGainDb, -24.f, 24.f) / 20.f);
    float drive = clampf(fx->p.drive, 0.f, 1.f);
    float mix = clampf(fx->p.mix, 0.f, 1.f);
    float driveNorm = drive > 0.001f ? 1.f / tanhf(1.f + drive * 8.f) : 1.f;

    for (size_t i = 0; i < processed; i++) {
        for (int c = 0; c < ch; c++) {
            float x;
            if (fx->format == AUDIO_FORMAT_PCM_16_BIT) {
                x = (float)in->s16[i * ch + c] * (1.f / 32768.f);
            } else {
                x = in->f32[i * ch + c];
            }
            float dry = x;

            fx->dcY[c] = x - fx->dcX[c] + 0.995f * fx->dcY[c];
            fx->dcX[c] = x;
            x = fx->dcY[c] * inGain;

            x = fx_gate(fx, c, x);
            x = bq_run(&fx->eqLow[c], x);
            x = bq_run(&fx->bodyLo[c], x);
            x = bq_run(&fx->eqMid[c], x);
            x = bq_run(&fx->bodyHi[c], x);
            x = bq_run(&fx->eqHigh[c], x);
            x = fx_comp(fx, c, x);

            if (drive > 0.001f) {
                float g = 1.f + drive * 8.f;
                x = tanhf(x * g) * driveNorm * (1.f - drive * 0.25f);
            }

            x = fx_pitch(fx, c, x);
            if (c == 0) x = fx_reverb(fx, x);

            x = dry * (1.f - mix) + x * mix;
            x = clampf(x, -1.f, 1.f);

            if (fx->format == AUDIO_FORMAT_PCM_16_BIT) {
                float v = x * 32767.f;
                out->s16[i * ch + c] =
                    (int16_t)(v > 32767.f ? 32767.f : (v < -32768.f ? -32768.f : v));
            } else {
                out->f32[i * ch + c] = x;
            }
        }
    }

    /* zero the unprocessed tail rather than echo stale samples */
    if (frames > processed) {
        size_t from = processed * (size_t)ch;
        size_t total = frames * (size_t)ch;
        if (fx->format == AUDIO_FORMAT_PCM_16_BIT) {
            memset(out->s16 + from, 0, (total - from) * sizeof(int16_t));
        } else {
            memset(out->f32 + from, 0, (total - from) * sizeof(float));
        }
    }
    return 0;
}

static int32_t fx_command(effect_handle_t self, uint32_t cmd, uint32_t size,
                          void* data, uint32_t* replySize, void* reply) {
    fx_t* fx = (fx_t*)self;
    if (!fx) return -EINVAL;
    int32_t status = 0;

    switch (cmd) {
        case EFFECT_CMD_INIT:
            fx_reset_state(fx);
            break;

        case EFFECT_CMD_SET_CONFIG:
        case EFFECT_CMD_SET_CONFIG_REVERSE:
            if (!data || size < sizeof(effect_config_t)) return -EINVAL;
            status = fx_configure(fx, (const effect_config_t*)data);
            break;

        case EFFECT_CMD_GET_CONFIG:
            if (!reply || !replySize || *replySize < sizeof(effect_config_t)) return -EINVAL;
            memset(reply, 0, sizeof(effect_config_t));
            *replySize = sizeof(effect_config_t);
            return 0;

        case EFFECT_CMD_RESET:
            fx_reset_state(fx);
            break;

        case EFFECT_CMD_ENABLE:
            fx->enabled = 1;
            break;

        case EFFECT_CMD_DISABLE:
            fx->enabled = 0;
            break;

        case EFFECT_CMD_GET_PARAM: {
            if (!data || size < sizeof(effect_param_t) || !reply || !replySize) return -EINVAL;
            effect_param_t* p = (effect_param_t*)data;
            effect_param_t* r = (effect_param_t*)reply;
            if (p->psize < sizeof(int32_t) ||
                *replySize < sizeof(effect_param_t) + p->psize + sizeof(int32_t)) {
                return -EINVAL;
            }
            int32_t id = 0;
            memcpy(&id, p->data, sizeof(id));
            int32_t value = 0;
            if (id == 0) value = (int32_t)(fx->p.pitch * 100.f);
            r->status = 0;
            r->psize = p->psize;
            r->vsize = sizeof(int32_t);
            memcpy(r->data, p->data, p->psize);
            memcpy(r->data + p->psize, &value, sizeof(value));
            *replySize = sizeof(effect_param_t) + p->psize + sizeof(int32_t);
            return 0;
        }

        case EFFECT_CMD_SET_PARAM:
        case EFFECT_CMD_SET_PARAM_DEFERRED:
        case EFFECT_CMD_SET_PARAM_COMMIT:
        case EFFECT_CMD_SET_DEVICE:
        case EFFECT_CMD_SET_INPUT_DEVICE:
        case EFFECT_CMD_SET_VOLUME:
        case EFFECT_CMD_SET_AUDIO_MODE:
        case EFFECT_CMD_SET_AUDIO_SOURCE:
        case EFFECT_CMD_OFFLOAD:
            break;

        default:
            return -EINVAL;
    }

    if (reply && replySize) {
        if (*replySize >= sizeof(int32_t)) {
            memcpy(reply, &status, sizeof(int32_t));
            *replySize = sizeof(int32_t);
        } else {
            *replySize = 0;
        }
    }
    return 0;
}

static int32_t fx_get_descriptor(effect_handle_t self, effect_descriptor_t* d) {
    (void)self;
    if (!d) return -EINVAL;
    *d = g_descriptor;
    return 0;
}

static int32_t fx_process_reverse(effect_handle_t self, audio_buffer_t* in, audio_buffer_t* out) {
    (void)self;
    if (!in || !out) return -EINVAL;
    if (in->raw && out->raw && in->raw != out->raw) {
        /* reference stream is not used; copy so the framework sees valid data */
        memcpy(out->raw, in->raw, in->frameCount * sizeof(int16_t));
    }
    return -ENODATA;
}

static const struct effect_interface_s g_interface_impl = {
    fx_process,
    fx_command,
    fx_get_descriptor,
    fx_process_reverse,
};

/* ─── Library entry points ────────────────────────────────────────────────── */
static int32_t fx_create(const effect_uuid_t* uuid, int32_t sessionId, int32_t ioId,
                         effect_handle_t* pHandle) {
    (void)sessionId;
    (void)ioId;
    if (!uuid || !pHandle) return -EINVAL;
    if (memcmp(uuid, &g_descriptor.uuid, sizeof(effect_uuid_t)) != 0) return -ENOENT;

    fx_t* fx = (fx_t*)calloc(1, sizeof(fx_t));
    if (!fx) return -ENOMEM;

    fx->itfe = &g_interface_impl;
    fx->enabled = 1;
    fx->sr = 48000;
    fx->channels = 1;
    fx->format = AUDIO_FORMAT_PCM_16_BIT;
    fx->p.magic = PARAM_MAGIC;
    fx->p.version = PARAM_VERSION;
    fx->p.enabled = 1;
    fx->p.mix = 1.f;
    fx->p.gateDb = -60.f;
    fx->p.compDb = -18.f;
    fx->p.compRatio = 4.f;

    fx_params_t disk;
    if (fx_load_params(&disk) == 0) fx->p = disk;

    fx_apply_coeffs(fx);
    fx_reset_state(fx);
    *pHandle = (effect_handle_t)fx;
    LOGI("instance created");
    return 0;
}

static int32_t fx_release(effect_handle_t handle) {
    if (!handle) return -EINVAL;
    free((fx_t*)handle);
    return 0;
}

static int32_t fx_get_descriptor_lib(const effect_uuid_t* uuid, effect_descriptor_t* d) {
    if (!uuid || !d) return -EINVAL;
    if (memcmp(uuid, &g_descriptor.uuid, sizeof(effect_uuid_t)) != 0) return -ENOENT;
    *d = g_descriptor;
    return 0;
}

static int32_t fx_create_3_1(const effect_uuid_t* uuid, int32_t sessionId, int32_t ioId,
                             int32_t deviceId, effect_handle_t* pHandle) {
    (void)deviceId;
    return fx_create(uuid, sessionId, ioId, pHandle);
}

__attribute__((visibility("default"))) audio_effect_library_t AUDIO_EFFECT_LIBRARY_INFO_SYM = {
    .tag = AUDIO_EFFECT_LIBRARY_TAG,
    .version = EFFECT_MAKE_API_VERSION(3, 1),
    .name = "MicUp++ Voice Engine",
    .implementor = "MicUp++",
    .create_effect = fx_create,
    .release_effect = fx_release,
    .get_descriptor = fx_get_descriptor_lib,
    .create_effect_3_1 = fx_create_3_1,
};
