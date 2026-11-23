#include "ClapHost.h"

#include <clap/clap.h>
#include <dlfcn.h>
#include <android/log.h>
#include <atomic>
#include <array>
#include <vector>
#include <cstring>
#include <cstdio>
#include <memory>
#include <mutex>

#define LOG_TAG "MicPlugin.CLAP"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace micplugin {

// ─── CLAP host callbacks ─────────────────────────────────────────────────────
static const void* clapHostGetExtension(const clap_host_t*, const char* /*extId*/) {
    // Return nullptr for optional extensions we don't implement.
    return nullptr;
}
static void clapHostRequestRestart(const clap_host_t*) {}
static void clapHostRequestProcess(const clap_host_t*) {}
static void clapHostRequestCallback(const clap_host_t*) {}

static const clap_host_t kClapHost = {
    CLAP_VERSION_INIT,
    nullptr,
    "MicUp++",
    "MicUp++",
    "https://github.com/hesucrysto/MicUpPlus",
    "2.0.0",
    clapHostGetExtension,
    clapHostRequestRestart,
    clapHostRequestProcess,
    clapHostRequestCallback,
};

static std::string jsonEscape(const std::string& in) {
    std::string out;
    out.reserve(in.size() + 8);
    for (char c : in) {
        switch (c) {
            case '"':  out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n";  break;
            case '\r': out += "\\r";  break;
            case '\t': out += "\\t";  break;
            default:   out += c;      break;
        }
    }
    return out;
}

// ─── CLAP plugin instance ────────────────────────────────────────────────────
struct ClapPluginInstance : public PluginInstance {
    void*                    dlib   = nullptr;
    const clap_plugin_t*     plugin = nullptr;
    const clap_plugin_params_t* paramsExt = nullptr;
    const clap_plugin_entry_t*  entry = nullptr;

    static constexpr uint32_t kMaxParams = 256;

    struct ParamUpdate {
        clap_id id = 0;
        double  value = 0.0;
    };

    std::mutex                 paramMutex;
    std::array<ParamUpdate, kMaxParams> pendingParams{};
    uint32_t                   pendingCount = 0;

    // Fixed event storage for one process block.
    struct InputEvents {
        clap_input_events_t iface{};
        clap_event_param_value_t events[kMaxParams]{};
        uint32_t count = 0;
    } inputEvents;

    clap_output_events_t outputEvents{};

    clap_audio_buffer_t inBuf{};
    clap_audio_buffer_t outBuf{};
    float* channelIn[1]  = {nullptr};
    float* channelOut[1] = {nullptr};
    std::vector<float> inStorage;
    std::vector<float> outStorage;
    clap_process_t proc{};
    int64_t samplePos = 0;

    ClapPluginInstance() {
        inputEvents.iface.ctx  = &inputEvents;
        inputEvents.iface.size = [](const clap_input_events_t* list) -> uint32_t {
            auto* self = static_cast<InputEvents*>(list->ctx);
            return self ? self->count : 0;
        };
        inputEvents.iface.get = [](const clap_input_events_t* list, uint32_t index) -> const clap_event_header_t* {
            auto* self = static_cast<InputEvents*>(list->ctx);
            if (!self || index >= self->count) return nullptr;
            return &self->events[index].header;
        };

        outputEvents.ctx = this;
        outputEvents.try_push = [](const clap_output_events_t*, const clap_event_header_t*) -> bool {
            // Host does not need plugin-emitted events for audio processing.
            return true;
        };
    }

    bool process(float* buf, int32_t frames, int32_t /*sr*/) override {
        if (!plugin || !plugin->process) return false;
        if (frames <= 0 || (size_t)frames > inStorage.size()) return false;

        std::memcpy(inStorage.data(), buf, (size_t)frames * sizeof(float));
        std::fill(outStorage.begin(), outStorage.begin() + frames, 0.0f);

        channelIn[0]  = inStorage.data();
        channelOut[0] = outStorage.data();

        inBuf.data32        = channelIn;
        inBuf.data64        = nullptr;
        inBuf.channel_count = 1;
        inBuf.latency       = 0;
        inBuf.constant_mask = 0;

        outBuf.data32        = channelOut;
        outBuf.data64        = nullptr;
        outBuf.channel_count = 1;
        outBuf.latency       = 0;
        outBuf.constant_mask = 0;

        // Collect parameter changes from the UI thread. Try-lock: never block the
        // audio callback behind the UI; a missed update is re-sent next block
        // because the UI keeps the value in PluginChain.
        inputEvents.count = 0;
        {
            std::unique_lock<std::mutex> lock(paramMutex, std::try_to_lock);
            if (lock.owns_lock()) {
                for (uint32_t i = 0; i < pendingCount && inputEvents.count < kMaxParams; i++) {
                    auto& ev = inputEvents.events[inputEvents.count++];
                    std::memset(&ev, 0, sizeof(ev));
                    ev.header.size     = sizeof(ev);
                    ev.header.time     = 0;
                    ev.header.space_id = CLAP_CORE_EVENT_SPACE_ID;
                    ev.header.type     = CLAP_EVENT_PARAM_VALUE;
                    ev.header.flags    = CLAP_EVENT_IS_LIVE;
                    ev.param_id        = pendingParams[i].id;
                    ev.cookie          = nullptr;
                    // CLAP: -1 means "this note/port/channel/key does not apply";
                    // a global parameter event must not target note 0.
                    ev.note_id         = -1;
                    ev.port_index      = -1;
                    ev.channel         = -1;
                    ev.key             = -1;
                    ev.value           = pendingParams[i].value;
                }
                pendingCount = 0;
            }
        }

        proc.steady_time         = samplePos;
        proc.frames_count        = (uint32_t)frames;
        proc.audio_inputs        = &inBuf;
        proc.audio_inputs_count  = 1;
        proc.audio_outputs       = &outBuf;
        proc.audio_outputs_count = 1;
        proc.in_events           = &inputEvents.iface;
        proc.out_events          = &outputEvents;
        proc.transport           = nullptr;

        clap_process_status status = plugin->process(plugin, &proc);
        if (status == CLAP_PROCESS_ERROR) return false;

        if (status == CLAP_PROCESS_SLEEP) {
            std::fill(outStorage.begin(), outStorage.begin() + frames, 0.0f);
        }

        std::memcpy(buf, outStorage.data(), (size_t)frames * sizeof(float));
        samplePos += frames;
        return true;
    }

    void setParam(int32_t id, float value) override {
        if (!paramsExt || id < 0) return;
        std::lock_guard<std::mutex> lock(paramMutex);
        // Coalesce repeated moves of the same parameter into one event.
        for (uint32_t i = 0; i < pendingCount; i++) {
            if (pendingParams[i].id == (clap_id)id) {
                pendingParams[i].value = (double)value;
                return;
            }
        }
        if (pendingCount < pendingParams.size()) {
            pendingParams[pendingCount++] = {(clap_id)id, (double)value};
        }
    }

    void cleanup() override {
        if (plugin) {
            if (plugin->stop_processing) plugin->stop_processing(plugin);
            if (plugin->deactivate)     plugin->deactivate(plugin);
            plugin->destroy(plugin);
            plugin = nullptr;
        }
        if (entry) {
            entry->deinit();
            entry = nullptr;
        }
        if (dlib) { dlclose(dlib); dlib = nullptr; }
    }
};

// ─── Load ───────────────────────────────────────────────────────────────────
std::shared_ptr<PluginInstance> ClapHost::load(const char* path, int32_t sampleRate) {
    void* dlib = dlopen(path, RTLD_NOW | RTLD_LOCAL);
    if (!dlib) {
        LOGE("dlopen failed: %s — %s", path, dlerror());
        return nullptr;
    }

    auto* entry = reinterpret_cast<const clap_plugin_entry_t*>(dlsym(dlib, "clap_entry"));
    if (!entry) {
        LOGE("clap_entry not found in: %s", path);
        dlclose(dlib);
        return nullptr;
    }

    if (!entry->init(path)) {
        LOGE("clap_entry.init failed: %s", path);
        dlclose(dlib);
        return nullptr;
    }

    const clap_plugin_factory_t* factory = reinterpret_cast<const clap_plugin_factory_t*>(
        entry->get_factory(CLAP_PLUGIN_FACTORY_ID));
    if (!factory || factory->get_plugin_count(factory) == 0) {
        LOGE("No CLAP plugins in factory: %s", path);
        entry->deinit();
        dlclose(dlib);
        return nullptr;
    }

    const clap_plugin_descriptor_t* desc = factory->get_plugin_descriptor(factory, 0);
    if (!desc) { entry->deinit(); dlclose(dlib); return nullptr; }

    LOGI("CLAP plugin found: %s (%s)", desc->name, desc->id);

    const clap_plugin_t* plugin = factory->create_plugin(factory, &kClapHost, desc->id);
    if (!plugin) { entry->deinit(); dlclose(dlib); return nullptr; }

    if (!plugin->init(plugin)) {
        plugin->destroy(plugin);
        entry->deinit();
        dlclose(dlib);
        return nullptr;
    }

    if (!plugin->activate(plugin, sampleRate, 32, 4096)) {
        plugin->destroy(plugin);
        entry->deinit();
        dlclose(dlib);
        return nullptr;
    }

    plugin->start_processing(plugin);

    auto instance        = std::make_shared<ClapPluginInstance>();
    instance->dlib       = dlib;
    instance->plugin     = plugin;
    instance->entry      = entry;
    instance->format     = PluginFormat::CLAP;
    instance->paramsExt  = reinterpret_cast<const clap_plugin_params_t*>(
        plugin->get_extension(plugin, CLAP_EXT_PARAMS));
    instance->inStorage.resize(4096, 0.0f);
    instance->outStorage.resize(4096, 0.0f);
    return instance;
}

// ─── Query params from a loaded CLAP instance ───────────────────────────────
// Returns JSON: [{"id":0,"name":"Gain","min":0.0,"max":1.0,"default":0.5}, ...]
std::string ClapHost::getParams(const std::shared_ptr<PluginInstance>& inst) {
    auto* ci = static_cast<ClapPluginInstance*>(inst.get());
    if (!ci || !ci->paramsExt || !ci->plugin) return "[]";

    uint32_t count = ci->paramsExt->count(ci->plugin);
    if (count == 0) return "[]";

    std::string json = "[";
    bool first = true;
    for (uint32_t i = 0; i < count; i++) {
        clap_param_info_t info{};
        if (!ci->paramsExt->get_info(ci->plugin, i, &info)) continue;

        double val = info.default_value;
        if (ci->paramsExt->get_value)
            ci->paramsExt->get_value(ci->plugin, info.id, &val);

        if (!first) json += ",";
        first = false;
        json += "{";
        json += "\"id\":" + std::to_string(info.id) + ",";
        json += "\"name\":\"" + jsonEscape(info.name) + "\",";
        json += "\"min\":"     + std::to_string(info.min_value) + ",";
        json += "\"max\":"     + std::to_string(info.max_value) + ",";
        json += "\"default\":" + std::to_string(val);
        json += "}";
    }
    json += "]";
    return json;
}

} // namespace micplugin
