#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <net.h>
#include <mat.h>
#include <option.h>
#include <jni.h>
#include <cstdio>
#include <vector>
#include <algorithm>
#include <chrono>
#include <cstdlib>
#include <csignal>
#include <csetjmp>
#include <omp.h>
#include <pthread.h>
#include <sched.h>
#include <cerrno>
#include <cstring>
#include <arm_neon.h>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "NcnnEngine", __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, "NcnnEngine", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "NcnnEngine", __VA_ARGS__)

static sigjmp_buf g_abort_jmp;
static volatile sig_atomic_t g_abort_expected = 0;

static void abort_handler(int sig) {
    if (g_abort_expected) {
        siglongjmp(g_abort_jmp, 1);
    }
}

static void suppress_omp_affinity() {
    setenv("KMP_AFFINITY", "disabled", 1);
    setenv("OMP_PROC_BIND", "false", 1);
    setenv("OMP_WAIT_POLICY", "PASSIVE", 1);
    setenv("KMP_SETTINGS", "0", 1);
    omp_set_dynamic(0);
}

static void bind_to_high_cluster() {
    cpu_set_t cpuset;
    CPU_ZERO(&cpuset);
    // Bind to big cores (CPU4-7 on Dimensity 9300)
    CPU_SET(4, &cpuset);
    CPU_SET(5, &cpuset);
    CPU_SET(6, &cpuset);
    CPU_SET(7, &cpuset);
    if (sched_setaffinity(0, sizeof(cpuset), &cpuset) == 0) {
        LOGI("Bound to high-freq cluster CPU4-7");
    } else {
        LOGW("Failed to bind to CPU4-7: %s", strerror(errno));
    }
}

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* /* reserved */) {
    (void)vm;
    suppress_omp_affinity();

    struct sigaction sa, old_sa;
    sa.sa_handler = abort_handler;
    sa.sa_flags = 0;
    sigemptyset(&sa.sa_mask);
    sigaction(SIGABRT, &sa, &old_sa);

    g_abort_expected = 1;
    if (sigsetjmp(g_abort_jmp, 1) == 0) {
        #pragma omp parallel
        {
            int tid = omp_get_thread_num();
            LOGI("JNI_OnLoad OMP init tid=%d", tid);
        }
    } else {
        LOGI("JNI_OnLoad: OMP affinity SIGABRT suppressed");
    }
    g_abort_expected = 0;
    sigaction(SIGABRT, &old_sa, nullptr);

    LOGI("JNI_OnLoad: OMP threads=4, affinity suppressed");
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT void JNICALL
Java_com_dutyclock_core_NcnnEngine_nativeInitThread(JNIEnv* env, jclass clazz) {
    suppress_omp_affinity();

    struct sigaction sa, old_sa;
    sa.sa_handler = abort_handler;
    sa.sa_flags = 0;
    sigemptyset(&sa.sa_mask);
    sigaction(SIGABRT, &sa, &old_sa);

    g_abort_expected = 1;
    if (sigsetjmp(g_abort_jmp, 1) == 0) {
        #pragma omp parallel
        {
            int tid = omp_get_thread_num();
            LOGI("nativeInitThread OMP tid=%d", tid);
        }
    } else {
        LOGI("nativeInitThread: OMP affinity SIGABRT suppressed");
    }
    g_abort_expected = 0;
    sigaction(SIGABRT, &old_sa, nullptr);
}

static ncnn::Net* g_net = nullptr;
static bool g_use_gpu = false;
static int g_target_size = 320;

static bool load_asset_to_tmpfile(AAssetManager* mgr, const char* name, FILE** out_f) {
    AAsset* asset = AAssetManager_open(mgr, name, AASSET_MODE_BUFFER);
    if (!asset) {
        LOGE("Failed to open %s", name);
        return false;
    }
    int len = AAsset_getLength(asset);
    std::vector<char> data(len);
    memcpy(data.data(), AAsset_getBuffer(asset), len);
    AAsset_close(asset);

    FILE* f = tmpfile();
    fwrite(data.data(), 1, len, f);
    rewind(f);
    *out_f = f;
    return true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_dutyclock_core_NcnnEngine_nativeLoadModel(JNIEnv* env, jclass clazz, jobject asset_manager, jboolean use_gpu, jint input_size) {

    suppress_omp_affinity();

    struct sigaction sa, old_sa;
    sa.sa_handler = abort_handler;
    sa.sa_flags = 0;
    sigemptyset(&sa.sa_mask);
    sigaction(SIGABRT, &sa, &old_sa);
    g_abort_expected = 1;

    AAssetManager* mgr = AAssetManager_fromJava(env, asset_manager);
    if (!mgr) {
        LOGE("Failed to get AssetManager");
        g_abort_expected = 0;
        sigaction(SIGABRT, &old_sa, nullptr);
        return JNI_FALSE;
    }

    if (g_net) {
        delete g_net;
        g_net = nullptr;
    }

    g_net = new ncnn::Net();
    g_use_gpu = use_gpu;
    g_target_size = input_size;

    ncnn::Option opt;
    opt.lightmode = true;
    opt.num_threads = 4;
    opt.openmp_blocktime = 20;
#if NCNN_VULKAN
    opt.use_vulkan_compute = g_use_gpu;
#endif
    opt.use_packing_layout = true;
    opt.use_winograd_convolution = true;
    opt.use_winograd23_convolution = true;
    opt.use_winograd43_convolution = true;
    opt.use_sgemm_convolution = true;
    opt.use_fp16_packed = true;
    opt.use_fp16_storage = true;
    opt.use_fp16_arithmetic = true;
    opt.use_int8_storage = true;
    opt.use_int8_packed = true;
    opt.use_int8_arithmetic = true;
    opt.use_int8_inference = true;
    opt.use_bf16_storage = true;
    opt.flush_denormals = 3;
    opt.use_a53_a55_optimized_kernel = false;

    g_net->opt = opt;

#if NCNN_VULKAN
    if (g_use_gpu) {
        int gpu_count = ncnn::get_gpu_count();
        if (gpu_count > 0) {
            ncnn::create_gpu_instance();
            g_net->set_vulkan_device(ncnn::get_gpu_device());
            LOGI("Vulkan GPU enabled: gpu_count=%d", gpu_count);
        } else {
            LOGW("Vulkan GPU not available, falling back to CPU");
            g_use_gpu = false;
            opt.use_vulkan_compute = false;
            g_net->opt = opt;
        }
    }
#endif

    const char* param_name;
    const char* bin_name;
    if (input_size == 128) {
        param_name = "yolo26n-pose-128.param";
        bin_name = "yolo26n-pose-128.bin";
    } else if (input_size == 160) {
        param_name = "yolo26n-pose-160.param";
        bin_name = "yolo26n-pose-160.bin";
    } else if (input_size == 320) {
        param_name = "yolo26n-pose-320.param";
        bin_name = "yolo26n-pose-320.bin";
    } else {
        param_name = "yolo26n-pose-320.param";
        bin_name = "yolo26n-pose-320.bin";
    }

    FILE* pf = nullptr;
    if (!load_asset_to_tmpfile(mgr, param_name, &pf)) {
        delete g_net; g_net = nullptr;
        g_abort_expected = 0; sigaction(SIGABRT, &old_sa, nullptr);
        return JNI_FALSE;
    }
    int ret = g_net->load_param(pf);
    fclose(pf);
    if (ret != 0) {
        LOGE("load_param failed: %d", ret);
        delete g_net; g_net = nullptr;
        g_abort_expected = 0; sigaction(SIGABRT, &old_sa, nullptr);
        return JNI_FALSE;
    }

    FILE* mf = nullptr;
    if (!load_asset_to_tmpfile(mgr, bin_name, &mf)) {
        delete g_net; g_net = nullptr;
        g_abort_expected = 0; sigaction(SIGABRT, &old_sa, nullptr);
        return JNI_FALSE;
    }
    ret = g_net->load_model(mf);
    fclose(mf);
    if (ret != 0) {
        LOGE("load_model failed: %d", ret);
        delete g_net; g_net = nullptr;
        g_abort_expected = 0; sigaction(SIGABRT, &old_sa, nullptr);
        return JNI_FALSE;
    }

    LOGI("NCNN model loaded! GPU=%d size=%d", g_use_gpu, g_target_size);
    g_abort_expected = 0; sigaction(SIGABRT, &old_sa, nullptr);
    return JNI_TRUE;
}

static void extract_results(ncnn::Mat& out, float conf_thresh, std::vector<float>& results) {
    int channels = out.h;
    int count = out.w;
    if (count <= 0 || channels <= 0) {
        results.push_back(0.0f);
        results.push_back(0.0f);
        return;
    }
    results.reserve(static_cast<size_t>(count) * 3 + 2);
    results.push_back((float)channels);
    results.push_back((float)count);
    float* out_data = (float*)out.data;
    for (int i = 0; i < count; i++) {
        float obj_conf = out_data[4 * count + i];
        if (obj_conf < conf_thresh) continue;
        results.push_back((float)i);
        results.push_back(obj_conf);
        for (int k = 0; k < 17; k++) {
            int base = 5 + k * 3;
            if (base + 2 < channels) {
                results.push_back(out_data[base * count + i]);
                results.push_back(out_data[(base + 1) * count + i]);
                results.push_back(out_data[(base + 2) * count + i]);
            }
        }
    }
}

static jfloatArray results_to_array(JNIEnv* env, std::vector<float>& results) {
    jfloatArray arr = env->NewFloatArray(results.size());
    env->SetFloatArrayRegion(arr, 0, results.size(), results.data());
    return arr;
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_dutyclock_core_NcnnEngine_nativeDetect(JNIEnv* env, jclass clazz,
    jintArray pixels_, jint width, jint height, jfloat conf_thresh) {
    if (!g_net) return nullptr;

    jint* pixels = (jint*)env->GetPrimitiveArrayCritical(pixels_, nullptr);
    if (!pixels) return nullptr;

    int w = width;
    int h = height;
    int ts = g_target_size;

    ncnn::Mat in = ncnn::Mat::from_pixels_resize((unsigned char*)pixels, ncnn::Mat::PIXEL_RGBA2RGB, w, h, ts, ts);
    env->ReleasePrimitiveArrayCritical(pixels_, pixels, JNI_ABORT);

    const float norm_vals[3] = {1.0f / 255.0f, 1.0f / 255.0f, 1.0f / 255.0f};
    in.substract_mean_normalize(0, norm_vals);

    ncnn::Extractor ex = g_net->create_extractor();
    ex.input("in0", in);

    ncnn::Mat out;
    ex.extract("out0", out);

    std::vector<float> results;
    extract_results(out, conf_thresh, results);
    return results_to_array(env, results);
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_dutyclock_core_NcnnEngine_nativeDetectBitmap(JNIEnv* env, jclass clazz,
    jobject bitmap, jint width, jint height, jfloat conf_thresh) {
    if (!g_net) return nullptr;

    AndroidBitmapInfo info;
    memset(&info, 0, sizeof(info));
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("AndroidBitmap_getInfo failed");
        return nullptr;
    }
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        LOGE("Unsupported bitmap format: %d", info.format);
        return nullptr;
    }

    void* pixels = nullptr;
    AndroidBitmap_lockPixels(env, bitmap, &pixels);
    if (!pixels) return nullptr;

    int ts = g_target_size;

    // Bitmap 已由 CameraManager 缩放为 320x320，使用 stride 对齐的 from_pixels
    int stride = info.stride;
    ncnn::Mat in = ncnn::Mat::from_pixels((unsigned char*)pixels, ncnn::Mat::PIXEL_RGBA2RGB, ts, ts, stride);
    AndroidBitmap_unlockPixels(env, bitmap);

    const float norm_vals[3] = {1.0f / 255.0f, 1.0f / 255.0f, 1.0f / 255.0f};
    in.substract_mean_normalize(0, norm_vals);

    ncnn::Extractor ex = g_net->create_extractor();
    ex.input("in0", in);

    ncnn::Mat out;
    ex.extract("out0", out);

    std::vector<float> results;
    extract_results(out, conf_thresh, results);
    return results_to_array(env, results);
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_dutyclock_core_NcnnEngine_nativeDetectRawBgra(JNIEnv* env, jclass clazz,
    jbyteArray bgra_data, jint width, jint height, jfloat conf_thresh) {
    if (!g_net) return nullptr;

    cpu_set_t orig_set;
    sched_getaffinity(0, sizeof(orig_set), &orig_set);
    cpu_set_t high_set;
    CPU_ZERO(&high_set);
    CPU_SET(4, &high_set);
    CPU_SET(5, &high_set);
    CPU_SET(6, &high_set);
    CPU_SET(7, &high_set);
    sched_setaffinity(0, sizeof(high_set), &high_set);

    struct AffinityGuard {
        cpu_set_t orig;
        bool active;
        AffinityGuard(const cpu_set_t& o) : orig(o), active(true) {}
        ~AffinityGuard() { if (active) sched_setaffinity(0, sizeof(orig), &orig); }
        void release() { active = false; }
    } guard(orig_set);

    jbyte* data = (jbyte*)env->GetPrimitiveArrayCritical(bgra_data, nullptr);
    if (!data) return nullptr;

    int w = width;
    int h = height;
    int ts = g_target_size;

    ncnn::Mat in = ncnn::Mat::from_pixels_resize((unsigned char*)data, ncnn::Mat::PIXEL_BGRA2RGB, w, h, ts, ts);
    env->ReleasePrimitiveArrayCritical(bgra_data, data, JNI_ABORT);

    const float norm_vals[3] = {1.0f / 255.0f, 1.0f / 255.0f, 1.0f / 255.0f};
    in.substract_mean_normalize(0, norm_vals);

    ncnn::Extractor ex = g_net->create_extractor();
    ex.input("in0", in);

    ncnn::Mat out;

    struct sigaction sa2, old_sa2;
    sa2.sa_handler = abort_handler;
    sa2.sa_flags = 0;
    sigemptyset(&sa2.sa_mask);
    sigaction(SIGABRT, &sa2, &old_sa2);
    g_abort_expected = 1;

    if (sigsetjmp(g_abort_jmp, 1) == 0) {
        ex.extract("out0", out);
    } else {
        sched_setaffinity(0, sizeof(orig_set), &orig_set);
        guard.release();
        sigaction(SIGABRT, &old_sa2, nullptr);
        g_abort_expected = 0;
        LOGE("nativeDetectRawBgra: OMP SIGABRT suppressed, returning null");
        return nullptr;
    }
    sigaction(SIGABRT, &old_sa2, nullptr);
    g_abort_expected = 0;

    std::vector<float> results;
    extract_results(out, conf_thresh, results);
    return results_to_array(env, results);
}

extern "C" JNIEXPORT jdouble JNICALL
Java_com_dutyclock_core_NcnnEngine_nativeBenchmark(JNIEnv* env, jclass clazz, jint warmup, jint runs) {
    if (!g_net) return -1.0;

    cpu_set_t orig_set;
    sched_getaffinity(0, sizeof(orig_set), &orig_set);
    bind_to_high_cluster();

    int ts = g_target_size;
    ncnn::Mat in(ts, ts, 3);
    in.fill(0.5f);

    for (int i = 0; i < warmup; i++) {
        ncnn::Extractor ex = g_net->create_extractor();
        ex.input("in0", in);
        ncnn::Mat out;
        ex.extract("out0", out);
    }

    double total_ms = 0.0;
    for (int i = 0; i < runs; i++) {
        auto t0 = std::chrono::high_resolution_clock::now();
        ncnn::Extractor ex = g_net->create_extractor();
        ex.input("in0", in);
        ncnn::Mat out;
        ex.extract("out0", out);
        auto t1 = std::chrono::high_resolution_clock::now();
        double ms = std::chrono::duration<double, std::milli>(t1 - t0).count();
        total_ms += ms;
    }

    sched_setaffinity(0, sizeof(orig_set), &orig_set);
    return total_ms / runs;
}

extern "C" JNIEXPORT void JNICALL
Java_com_dutyclock_core_NcnnEngine_nativeBgraToRgba(JNIEnv* env, jclass clazz, jbyteArray bgra_data, jint pixel_count) {
    jbyte* data = (jbyte*)env->GetPrimitiveArrayCritical(bgra_data, nullptr);
    if (!data) return;
    unsigned char* p = (unsigned char*)data;
    for (int i = 0; i < pixel_count; i++) {
        int off = i * 4;
        unsigned char b = p[off];
        p[off] = p[off + 2];
        p[off + 2] = b;
    }
    env->ReleasePrimitiveArrayCritical(bgra_data, data, 0);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_dutyclock_core_NcnnEngine_nativeIsVulkanAvailable(JNIEnv* env, jclass clazz) {
#if NCNN_VULKAN
    return ncnn::get_gpu_count() > 0 ? JNI_TRUE : JNI_FALSE;
#else
    return JNI_FALSE;
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_com_dutyclock_core_NcnnEngine_nativeRelease(JNIEnv* env, jclass clazz) {
#if NCNN_VULKAN
    if (g_use_gpu) {
        ncnn::destroy_gpu_instance();
    }
#endif
    if (g_net) {
        delete g_net;
        g_net = nullptr;
    }
    LOGI("NCNN released");
}