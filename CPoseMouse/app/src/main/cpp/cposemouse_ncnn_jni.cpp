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
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <unistd.h>
#include <pthread.h>
#include <sched.h>
#include <cerrno>
#include <cstring>
#include <arm_neon.h>
#include "ikcp.h"


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

static void bind_to_mid_cluster() {
    cpu_set_t cpuset;
    CPU_ZERO(&cpuset);
    CPU_SET(0, &cpuset);
    CPU_SET(1, &cpuset);
    CPU_SET(2, &cpuset);
    CPU_SET(3, &cpuset);
    if (sched_setaffinity(0, sizeof(cpuset), &cpuset) == 0) {
        LOGI("KCP thread bound to mid-freq cluster CPU0-3");
    } else {
        LOGW("Failed to bind to CPU0-3: %s", strerror(errno));
    }
}

static void bind_to_high_cluster() {
    cpu_set_t cpuset;
    CPU_ZERO(&cpuset);
    CPU_SET(4, &cpuset);
    CPU_SET(5, &cpuset);
    CPU_SET(6, &cpuset);
    CPU_SET(7, &cpuset);
    if (sched_setaffinity(0, sizeof(cpuset), &cpuset) == 0) {
        LOGI("Bound to high-freq cluster CPU4-7 (X4@3.25G + 3xA720@2.85G)");
    } else {
        LOGW("Failed to bind to CPU4-7: %s", strerror(errno));
    }
}

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* reserved) {
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

    LOGI("JNI_OnLoad: OMP threads=4, affinity suppressed, powersave=2");
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT void JNICALL
Java_com_CPoseMouse_core_NcnnEngine_nativeInitThread(JNIEnv* env, jclass clazz) {
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
static int g_target_size = 640;

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
Java_com_CPoseMouse_core_NcnnEngine_nativeLoadModel(JNIEnv* env, jclass clazz, jobject asset_manager, jboolean use_gpu, jint input_size) {

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

    LOGI("NCNN opt: threads=%d blocktime=%d pack=%d winograd=%d sgemm=%d fp16p=%d fp16s=%d fp16a=%d int8s=%d int8p=%d int8a=%d bf16=%d flush=%d a53=%d",
         opt.num_threads, opt.openmp_blocktime,
         opt.use_packing_layout, opt.use_winograd_convolution, opt.use_sgemm_convolution,
         opt.use_fp16_packed, opt.use_fp16_storage, opt.use_fp16_arithmetic,
         opt.use_int8_storage, opt.use_int8_packed, opt.use_int8_arithmetic,
         opt.use_bf16_storage, opt.flush_denormals, opt.use_a53_a55_optimized_kernel);

    g_net->opt = opt;

    const char* param_name;
    const char* bin_name;
    if (input_size == 128) {
        param_name = "yolo26n-pose-128.param";
        bin_name = "yolo26n-pose-128.bin";
    } else if (input_size == 160) {
        param_name = "yolo26n-pose-160.param";
        bin_name = "yolo26n-pose-160.bin";
    } else if (input_size == 320) {
        param_name = "yolo26s-pose-320.param";
        bin_name = "yolo26s-pose-320.bin";
    } else {
        param_name = "yolo26s-pose-320.param";
        bin_name = "yolo26s-pose-320.bin";
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
    results.reserve(count * 3 + 2);
    results.push_back((float)channels);
    results.push_back((float)count);
    for (int i = 0; i < count; i++) {
        float obj_conf = ((float*)out.data)[4 * count + i];
        if (obj_conf < conf_thresh) continue;
        results.push_back((float)i);
        results.push_back(obj_conf);
        for (int k = 0; k < 17; k++) {
            int base = 5 + k * 3;
            if (base + 2 < channels) {
                results.push_back(((float*)out.data)[base * count + i]);
                results.push_back(((float*)out.data)[(base + 1) * count + i]);
                results.push_back(((float*)out.data)[(base + 2) * count + i]);
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
Java_com_CPoseMouse_core_NcnnEngine_nativeDetect(JNIEnv* env, jclass clazz,
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
Java_com_CPoseMouse_core_NcnnEngine_nativeDetectBitmap(JNIEnv* env, jclass clazz,
    jobject bitmap, jint width, jint height, jfloat conf_thresh) {
    if (!g_net) return nullptr;

    AndroidBitmapInfo info;
    AndroidBitmap_getInfo(env, bitmap, &info);

    void* pixels = nullptr;
    AndroidBitmap_lockPixels(env, bitmap, &pixels);
    if (!pixels) return nullptr;

    int w = width;
    int h = height;
    int ts = g_target_size;

    ncnn::Mat in = ncnn::Mat::from_pixels_resize((unsigned char*)pixels, ncnn::Mat::PIXEL_RGBA2RGB, w, h, ts, ts);
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
Java_com_CPoseMouse_core_NcnnEngine_nativeDetectRawBgra(JNIEnv* env, jclass clazz,
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
Java_com_CPoseMouse_core_NcnnEngine_nativeBenchmark(JNIEnv* env, jclass clazz, jint warmup, jint runs) {
    if (!g_net) return -1.0;

    cpu_set_t orig_set;
    sched_getaffinity(0, sizeof(orig_set), &orig_set);
    cpu_set_t high_set;
    CPU_ZERO(&high_set);
    CPU_SET(4, &high_set);
    CPU_SET(5, &high_set);
    CPU_SET(6, &high_set);
    CPU_SET(7, &high_set);
    sched_setaffinity(0, sizeof(high_set), &high_set);
    LOGI("Benchmark bound to CPU4-7");

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
Java_com_CPoseMouse_core_H264Decoder_nativeYuv420ToBgra(
    JNIEnv* env, jclass clazz,
    jbyteArray yArr, jbyteArray uArr, jbyteArray vArr,
    jint width, jint height,
    jint yStride, jint uvStride, jint uvPixelStride,
    jbyteArray outBgra) {

    jbyte* yBuf = (jbyte*)env->GetPrimitiveArrayCritical(yArr, nullptr);
    jbyte* uBuf = (jbyte*)env->GetPrimitiveArrayCritical(uArr, nullptr);
    jbyte* vBuf = (jbyte*)env->GetPrimitiveArrayCritical(vArr, nullptr);
    jbyte* out = (jbyte*)env->GetPrimitiveArrayCritical(outBgra, nullptr);

    if (!yBuf || !uBuf || !vBuf || !out) {
        if (yBuf) env->ReleasePrimitiveArrayCritical(yArr, yBuf, JNI_ABORT);
        if (uBuf) env->ReleasePrimitiveArrayCritical(uArr, uBuf, JNI_ABORT);
        if (vBuf) env->ReleasePrimitiveArrayCritical(vArr, vBuf, JNI_ABORT);
        if (out) env->ReleasePrimitiveArrayCritical(outBgra, out, JNI_ABORT);
        return;
    }

    const int16x8_t v128 = vdupq_n_s16(128);
    const int16x8_t v0 = vdupq_n_s16(0);
    const int16x8_t v255 = vdupq_n_s16(255);

    for (int j = 0; j < height; j++) {
        int i = 0;
        for (; i <= width - 8; i += 8) {
            int yIdx = j * yStride + i;
            int uvIdx = (j / 2) * uvStride + (i / 2) * uvPixelStride;

            uint8x8_t y8 = vld1_u8((uint8_t*)(yBuf + yIdx));

            uint8_t uTmp[8], vTmp[8];
            for (int k = 0; k < 8; k++) {
                int ui = (j / 2) * uvStride + ((i + k) / 2) * uvPixelStride;
                uTmp[k] = *(uint8_t*)(uBuf + ui);
                vTmp[k] = *(uint8_t*)(vBuf + ui);
            }
            uint8x8_t u8 = vld1_u8(uTmp);
            uint8x8_t v8_ = vld1_u8(vTmp);

            int16x8_t y16 = vreinterpretq_s16_u16(vmovl_u8(y8));
            int16x8_t u16 = vsubq_s16(vreinterpretq_s16_u16(vmovl_u8(u8)), v128);
            int16x8_t v16 = vsubq_s16(vreinterpretq_s16_u16(vmovl_u8(v8_)), v128);

            int16x8_t r16 = vaddq_s16(y16, vshrq_n_s16(vshlq_n_s16(v16, 8), 6));
            int16x8_t g16 = vsubq_s16(y16, vaddq_s16(vshrq_n_s16(vshlq_n_s16(u16, 5), 7), vshrq_n_s16(vshlq_n_s16(v16, 8), 6)));
            int16x8_t b16 = vaddq_s16(y16, vshrq_n_s16(vshlq_n_s16(u16, 8), 6));

            r16 = vmaxq_s16(vminq_s16(r16, v255), v0);
            g16 = vmaxq_s16(vminq_s16(g16, v255), v0);
            b16 = vmaxq_s16(vminq_s16(b16, v255), v0);

            uint8x8_t r8 = vmovn_u16(vreinterpretq_u16_s16(r16));
            uint8x8_t g8 = vmovn_u16(vreinterpretq_u16_s16(g16));
            uint8x8_t b8 = vmovn_u16(vreinterpretq_u16_s16(b16));

            int outBase = (j * width + i) * 4;
            uint8_t* p = (uint8_t*)(out + outBase);
            uint8x8x4_t bgra;
            bgra.val[0] = b8;
            bgra.val[1] = g8;
            bgra.val[2] = r8;
            bgra.val[3] = vdup_n_u8(255);
            vst4_u8(p, bgra);
        }

        for (; i < width; i++) {
            int yIdx = j * yStride + i;
            int uvIdx = (j / 2) * uvStride + (i / 2) * uvPixelStride;
            int y = (yBuf[yIdx] & 0xFF);
            int u = (uBuf[uvIdx] & 0xFF) - 128;
            int v = (vBuf[uvIdx] & 0xFF) - 128;
            int r = y + ((v * 359) >> 8);
            int g = y - ((u * 88) >> 8) - ((v * 183) >> 8);
            int b = y + ((u * 454) >> 8);
            r = r < 0 ? 0 : (r > 255 ? 255 : r);
            g = g < 0 ? 0 : (g > 255 ? 255 : g);
            b = b < 0 ? 0 : (b > 255 ? 255 : b);
            int outIdx = (j * width + i) * 4;
            out[outIdx] = (jbyte)b;
            out[outIdx + 1] = (jbyte)g;
            out[outIdx + 2] = (jbyte)r;
            out[outIdx + 3] = (jbyte)-1;
        }
    }

    env->ReleasePrimitiveArrayCritical(yArr, yBuf, JNI_ABORT);
    env->ReleasePrimitiveArrayCritical(uArr, uBuf, JNI_ABORT);
    env->ReleasePrimitiveArrayCritical(vArr, vBuf, JNI_ABORT);
    env->ReleasePrimitiveArrayCritical(outBgra, out, JNI_ABORT);
}

extern "C" JNIEXPORT void JNICALL
Java_com_CPoseMouse_core_NcnnEngine_nativeBgraToRgba(JNIEnv* env, jclass clazz, jbyteArray bgra_data, jint pixel_count) {
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

extern "C" JNIEXPORT void JNICALL
Java_com_CPoseMouse_core_NcnnEngine_nativeRelease(JNIEnv* env, jclass clazz) {
    if (g_net) {
        delete g_net;
        g_net = nullptr;
    }
    LOGI("NCNN released");
}

/* ---- KCP Receiver ---- */

static int g_kcp_sock = -1;
static struct sockaddr_in g_kcp_dest_addr;
static ikcpcb* g_kcp = nullptr;
static volatile bool g_kcp_running = false;
static pthread_t g_kcp_thread;

static uint8_t* g_frame_buf = nullptr;
static int g_frame_buf_size = 0;

static volatile int g_frame_len = 0;
static volatile int g_frame_id = -1;
static volatile int g_frame_ready = 0;
static pthread_mutex_t g_frame_mutex = PTHREAD_MUTEX_INITIALIZER;
static uint64_t g_kcp_recv_count = 0;
static uint64_t g_kcp_recv_bytes = 0;

static int kcp_udp_send(const char* buf, int len, struct IKCPCB* kcp, void* user) {
    if (g_kcp_sock < 0) return -1;
    int ret = sendto(g_kcp_sock, buf, len, 0, (struct sockaddr*)&g_kcp_dest_addr, sizeof(g_kcp_dest_addr));
    return (ret < 0) ? -1 : 0;
}


static void* kcp_recv_thread(void* arg) {
    bind_to_mid_cluster();

    std::vector<uint8_t> recv_buf_vec(65536);
    uint8_t* recv_buf = recv_buf_vec.data();
    size_t recv_buf_size = recv_buf_vec.size();
    struct sockaddr_in from_addr;
    socklen_t from_len = sizeof(from_addr);

    LOGI("KCP recv thread started");

    while (g_kcp_running) {
        int batch_count = 0;
        while (g_kcp_running) {
            socklen_t fl = sizeof(from_addr);
            ssize_t n = recvfrom(g_kcp_sock, recv_buf, recv_buf_size, MSG_DONTWAIT,
                                 (struct sockaddr*)&from_addr, &fl);
            if (n <= 0) break;

            if (g_kcp) {
                ikcp_input(g_kcp, (const char*)recv_buf, (int)n);
                g_kcp_recv_count++;
                g_kcp_recv_bytes += n;
                batch_count++;

                if (g_kcp_recv_count == 1) {
                    memcpy(&g_kcp_dest_addr, &from_addr, sizeof(from_addr));
                    char addr_str[64];
                    inet_ntop(AF_INET, &from_addr.sin_addr, addr_str, sizeof(addr_str));
                    LOGI("KCP learned peer: %s:%d", addr_str, ntohs(from_addr.sin_port));
                }
            }
        }

        if (g_kcp) {
            uint32_t current_ms = (uint32_t)(std::chrono::duration_cast<std::chrono::milliseconds>(
                std::chrono::steady_clock::now().time_since_epoch()).count());
            ikcp_update(g_kcp, current_ms);

            while (true) {
                std::vector<char> kcp_buf_vec(65536);
                char* kcp_buf = kcp_buf_vec.data();
                int kcp_len = ikcp_recv(g_kcp, kcp_buf, 65536);
                if (kcp_len <= 0) break;

                if (kcp_len >= 16) {
                    uint32_t fi32; uint16_t pkt_idx, total_pkts; uint32_t raw_size, comp_size;
                    memcpy(&fi32, kcp_buf, 4);
                    memcpy(&pkt_idx, kcp_buf + 4, 2);
                    memcpy(&total_pkts, kcp_buf + 6, 2);
                    memcpy(&raw_size, kcp_buf + 8, 4);
                    memcpy(&comp_size, kcp_buf + 12, 4);

                    int data_len = kcp_len - 16;

                    if (data_len > 0) {
                        pthread_mutex_lock(&g_frame_mutex);
                        int needed = data_len + 16;
                        if (g_frame_buf_size < needed) {
                            delete[] g_frame_buf;
                            g_frame_buf = new uint8_t[needed + 4096];
                            g_frame_buf_size = needed + 4096;
                        }

                        memcpy(g_frame_buf, kcp_buf, 16);
                        memcpy(g_frame_buf + 16, kcp_buf + 16, data_len);

                        g_frame_len = data_len + 16;

                        g_frame_id = (int)fi32;
                        g_frame_ready = 1;
                        pthread_mutex_unlock(&g_frame_mutex);
                    }

                    static int kcp_recv_frame_log = 0;
                    kcp_recv_frame_log++;
                    if (kcp_recv_frame_log <= 10 || kcp_recv_frame_log % 100 == 0) {
                        LOGI("KCP recv frame #%d: fid=%u comp=%u raw=%u data_len=%d kcp_len=%d",
                             kcp_recv_frame_log, fi32, comp_size, raw_size, data_len, kcp_len);
                    }
                }
            }

            if (g_kcp_recv_count > 0 && g_kcp_recv_count % 500 == 0) {
                LOGI("KCP stats: input=%llu bytes=%llu nrcv_buf=%u nsnd_buf=%u nrcv_que=%u nsnd_que=%u",
                     (unsigned long long)g_kcp_recv_count, (unsigned long long)g_kcp_recv_bytes,
                     g_kcp->nrcv_buf, g_kcp->nsnd_buf, g_kcp->nrcv_que, g_kcp->nsnd_que);
            }
        }

        if (batch_count == 0) {
            usleep(500);
        }
    }

    LOGI("KCP recv thread stopped");
    return nullptr;
}

static void kcp_stop_internal() {
    g_kcp_running = false;
    if (g_kcp_thread) {
        pthread_join(g_kcp_thread, nullptr);
        g_kcp_thread = 0;
    }
    if (g_kcp) {
        ikcp_release(g_kcp);
        g_kcp = nullptr;
    }
    if (g_kcp_sock >= 0) {
        close(g_kcp_sock);
        g_kcp_sock = -1;
    }
    if (g_frame_buf) {
        delete[] g_frame_buf;
        g_frame_buf = nullptr;
    }
    g_frame_buf_size = 0;
    g_frame_ready = 0;
    g_frame_id = -1;

    g_frame_len = 0;
    g_kcp_recv_count = 0;
    g_kcp_recv_bytes = 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_CPoseMouse_core_UdpFrameReceiver_nativeKcpStart(JNIEnv* env, jclass clazz, jint port, jint conv) {
    if (g_kcp_running) {
        kcp_stop_internal();
        usleep(100000);
    }

    if (g_kcp_sock >= 0) {
        close(g_kcp_sock);
        g_kcp_sock = -1;
    }
    if (g_kcp) {
        ikcp_release(g_kcp);
        g_kcp = nullptr;
    }
    if (g_frame_buf) {
        delete[] g_frame_buf;
        g_frame_buf = nullptr;
        g_frame_buf_size = 0;
    }

    g_kcp_sock = socket(AF_INET, SOCK_DGRAM, 0);
    if (g_kcp_sock < 0) {
        LOGE("KCP socket create failed: %s", strerror(errno));
        return;
    }

    int rcvbuf = 8 * 1024 * 1024;
    setsockopt(g_kcp_sock, SOL_SOCKET, SO_RCVBUF, &rcvbuf, sizeof(rcvbuf));

    struct timeval tv;
    tv.tv_sec = 0; tv.tv_usec = 500000;
    setsockopt(g_kcp_sock, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));

    struct sockaddr_in local_addr;
    memset(&local_addr, 0, sizeof(local_addr));
    local_addr.sin_family = AF_INET;
    local_addr.sin_port = htons((uint16_t)port);
    local_addr.sin_addr.s_addr = INADDR_ANY;

    if (bind(g_kcp_sock, (struct sockaddr*)&local_addr, sizeof(local_addr)) < 0) {
        LOGE("KCP bind failed: %s", strerror(errno));
        close(g_kcp_sock);
        g_kcp_sock = -1;
        return;
    }

    memset(&g_kcp_dest_addr, 0, sizeof(g_kcp_dest_addr));
    g_kcp_dest_addr.sin_family = AF_INET;
    g_kcp_dest_addr.sin_port = htons((uint16_t)port);
    g_kcp_dest_addr.sin_addr.s_addr = htonl(INADDR_ANY);

    {
        uint8_t drain_buf[65536];
        struct sockaddr_in drain_addr;
        socklen_t drain_len = sizeof(drain_addr);
        int drain_count = 0;
        while (true) {
            ssize_t dn = recvfrom(g_kcp_sock, drain_buf, sizeof(drain_buf), MSG_DONTWAIT,
                                  (struct sockaddr*)&drain_addr, &drain_len);
            if (dn <= 0) break;
            drain_count++;
        }
        LOGI("KCP drained %d stale packets from socket", drain_count);
    }

    g_kcp = ikcp_create((IUINT32)conv, nullptr);
    if (g_kcp) {
        ikcp_nodelay(g_kcp, 1, 1, 2, 1);
        ikcp_setmtu(g_kcp, 1400);
        ikcp_wndsize(g_kcp, 4096, 4096);
        g_kcp->stream = 0;
        g_kcp->output = kcp_udp_send;
    }

    g_kcp_recv_count = 0;
    g_kcp_recv_bytes = 0;
    g_frame_ready = 0;
    g_kcp_running = true;

    pthread_create(&g_kcp_thread, nullptr, kcp_recv_thread, nullptr);
    LOGI("KCP started: port=%d conv=0x%08X", port, conv);
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_CPoseMouse_core_UdpFrameReceiver_nativeKcpGetFrame(JNIEnv* env, jclass clazz, jbyteArray out_buf) {
    pthread_mutex_lock(&g_frame_mutex);
    if (!g_frame_ready || g_frame_len <= 16) {
        pthread_mutex_unlock(&g_frame_mutex);
        return nullptr;
    }

    jbyte* out = (jbyte*)env->GetPrimitiveArrayCritical(out_buf, nullptr);
    if (out) {
        memcpy(out, g_frame_buf, g_frame_len);
        env->ReleasePrimitiveArrayCritical(out_buf, out, 0);
    }

    uint32_t fi32, raw_size, comp_size;
    uint16_t pkt_idx, total_pkts;
    memcpy(&fi32, g_frame_buf, 4);
    memcpy(&pkt_idx, g_frame_buf + 4, 2);
    memcpy(&total_pkts, g_frame_buf + 6, 2);
    memcpy(&raw_size, g_frame_buf + 8, 4);
    memcpy(&comp_size, g_frame_buf + 12, 4);

    jint info[5] = {(jint)fi32, (jint)raw_size, (jint)comp_size, (jint)(g_frame_len - 16), (jint)total_pkts};
    g_frame_ready = 0;
    pthread_mutex_unlock(&g_frame_mutex);

    jintArray result = env->NewIntArray(5);
    env->SetIntArrayRegion(result, 0, 5, info);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_CPoseMouse_core_UdpFrameReceiver_nativeKcpStop(JNIEnv* env, jclass clazz) {
    kcp_stop_internal();
    LOGI("KCP stopped");
}
