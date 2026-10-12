/*
 * Catroid: An on-device visual programming system for Android devices
 * Copyright (C) 2010-2024 The Catrobat Team
 * (<http://developer.catrobat.org/credits>)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * An additional term exception under section 7 of the GNU Affero
 * General Public License, version 3, is available at
 * http://developer.catrobat.org/license_additional_term
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

/*
 * PC runtime native support.
 *
 * Two things live here:
 *
 *  1. A self-contained Vulkan capability probe. It dlopen()s the platform
 *     loader (libvulkan.so) and only calls symbols that are part of core
 *     Vulkan 1.0 plus the versioned feature structs, so it keeps working on
 *     old and new drivers alike. It never links against libvulkan: if a device
 *     has no loader at all we must be able to report that instead of failing to
 *     load libcatroid.so.
 *
 *  2. AArch64 CPU feature reporting, used as the native smoke test surface
 *     inside the main library.
 *
 * The result is a JSON document; parsing/decision making lives in Kotlin where
 * it is unit testable without a device.
 */

#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <string>
#include <utility>
#include <vector>
#include <sys/auxv.h>

#define PCRUNTAG "PcRuntimeNative"

#define PCRUN_LOG(...) __android_log_print(ANDROID_LOG_INFO, PCRUNTAG, __VA_ARGS__)

#if defined(__has_include)
#  if __has_include(<vulkan/vulkan.h>)
#    include <vulkan/vulkan.h>
#    define PCRUN_HAS_VULKAN_HEADERS 1
#  endif
#endif

#ifndef PCRUN_HAS_VULKAN_HEADERS
#define PCRUN_HAS_VULKAN_HEADERS 0
#endif

namespace {

std::string jsonEscape(const std::string& input) {
    std::string out;
    out.reserve(input.size() + 8);
    for (char c : input) {
        switch (c) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default:
                if (static_cast<unsigned char>(c) < 0x20) {
                    char buf[8];
                    snprintf(buf, sizeof(buf), "\\u%04x", c);
                    out += buf;
                } else {
                    out += c;
                }
        }
    }
    return out;
}

std::string quote(const std::string& input) {
    return "\"" + jsonEscape(input) + "\"";
}

// VK_API_VERSION_MAJOR/VK_API_VERSION_MINOR are missing from some of the
// header generations the Android NDK has shipped, so the two fields are
// extracted locally (identical layout to the Vulkan specification).
static inline uint32_t apiVersionMajor(uint32_t apiVersion) {
    return (apiVersion >> 22) & 0x7FU;
}

static inline uint32_t apiVersionMinor(uint32_t apiVersion) {
    return (apiVersion >> 12) & 0x3FFU;
}

std::string jsonError(const std::string& message) {
    return "{\"available\":false,\"error\":" + quote(message) + "}";
}

#if PCRUN_HAS_VULKAN_HEADERS

typedef PFN_vkVoidFunction (*PFN_vkGetInstanceProcAddr_t)(VkInstance, const char*);

const char* deviceTypeName(VkPhysicalDeviceType type) {
    switch (type) {
        case VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU: return "integrated";
        case VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU: return "discrete";
        case VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU: return "virtual";
        case VK_PHYSICAL_DEVICE_TYPE_CPU: return "cpu";
        default: return "other";
    }
}

/**
 * Ordered name -> number map that is serialised as a JSON *object*.
 *
 * The Kotlin side reads this with JSONObject.optJSONObject("features"), so an
 * object (not an array) is the contract, and a boolean feature has to be the
 * number 1 to be counted as enabled. Numeric limits are emitted as their real
 * count; a limit entry only exists when the structure it came from was really
 * filled in, so "not queried" and "driver answered zero" stay distinguishable.
 */
class FeatureBag {
public:
    void flag(const char* name, unsigned int value) {
        entries.emplace_back(name, value ? 1ULL : 0ULL);
    }

    void limit(const char* name, unsigned long long value) {
        entries.emplace_back(name, value);
    }

    std::string toJson() const {
        std::string out = "{";
        for (size_t i = 0; i < entries.size(); ++i) {
            if (i > 0) {
                out += ",";
            }
            out += quote(entries[i].first);
            out += ":";
            out += std::to_string(entries[i].second);
        }
        out += "}";
        return out;
    }

private:
    std::vector<std::pair<std::string, unsigned long long>> entries;
};

std::string probeVulkan() {
    void* loader = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
    if (loader == nullptr) {
        loader = dlopen("libvulkan.so.1", RTLD_NOW | RTLD_LOCAL);
    }
    if (loader == nullptr) {
        return jsonError("libvulkan.so could not be loaded on this device");
    }

    auto getInstanceProcAddr = reinterpret_cast<PFN_vkGetInstanceProcAddr_t>(
            dlsym(loader, "vkGetInstanceProcAddr"));
    if (getInstanceProcAddr == nullptr) {
        dlclose(loader);
        return jsonError("libvulkan.so does not export vkGetInstanceProcAddr");
    }

    auto createInstance = reinterpret_cast<PFN_vkCreateInstance>(
            getInstanceProcAddr(nullptr, "vkCreateInstance"));
    auto enumerateInstanceVersion = reinterpret_cast<PFN_vkEnumerateInstanceVersion>(
            getInstanceProcAddr(nullptr, "vkEnumerateInstanceVersion"));
    auto enumerateInstanceExtensionProperties = reinterpret_cast<PFN_vkEnumerateInstanceExtensionProperties>(
            getInstanceProcAddr(nullptr, "vkEnumerateInstanceExtensionProperties"));

    if (createInstance == nullptr) {
        dlclose(loader);
        return jsonError("Vulkan loader is present but vkCreateInstance is unavailable");
    }

    uint32_t loaderApiVersion = VK_API_VERSION_1_0;
    if (enumerateInstanceVersion != nullptr) {
        enumerateInstanceVersion(&loaderApiVersion);
    }

    // Querying driver properties requires VK_KHR_get_physical_device_properties2
    // on a 1.0 loader and is core from 1.1 on.
    std::string instanceExtensions = "[";
    bool firstExt = true;
    const char* props2Extension = "VK_KHR_get_physical_device_properties2";
    if (enumerateInstanceExtensionProperties != nullptr) {
        uint32_t count = 0;
        if (enumerateInstanceExtensionProperties(nullptr, &count, nullptr) == VK_SUCCESS && count > 0) {
            VkExtensionProperties* available = new VkExtensionProperties[count];
            if (enumerateInstanceExtensionProperties(nullptr, &count, available) == VK_SUCCESS) {
                for (uint32_t i = 0; i < count; ++i) {
                    if (!firstExt) instanceExtensions += ",";
                    firstExt = false;
                    instanceExtensions += quote(available[i].extensionName);
                }
            }
            delete[] available;
        }
    }
    instanceExtensions += "]";

    // vkGetPhysicalDeviceProperties2 is core from Vulkan 1.1 onwards. On a 1.0
    // loader the equivalent functionality has to be requested through the
    // VK_KHR_get_physical_device_properties2 instance extension instead.
    const char* enabledExtensions[1];
    uint32_t enabledExtensionCount = 0;
    if (apiVersionMajor(loaderApiVersion) == 1 && apiVersionMinor(loaderApiVersion) == 0 &&
        instanceExtensions.find(props2Extension) != std::string::npos) {
        enabledExtensions[0] = props2Extension;
        enabledExtensionCount = 1;
    }

    VkApplicationInfo appInfo{};
    appInfo.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    appInfo.pApplicationName = "NewCatroid PcRuntime Probe";
    appInfo.applicationVersion = VK_MAKE_VERSION(1, 0, 0);
    appInfo.pEngineName = "NewCatroid";
    appInfo.engineVersion = VK_MAKE_VERSION(1, 0, 0);
    appInfo.apiVersion = loaderApiVersion;

    VkInstanceCreateInfo createInfo{};
    createInfo.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    createInfo.pApplicationInfo = &appInfo;
    createInfo.enabledExtensionCount = enabledExtensionCount;
    createInfo.ppEnabledExtensionNames = enabledExtensionCount > 0 ? enabledExtensions : nullptr;

    VkInstance instance = VK_NULL_HANDLE;
    VkResult createResult = createInstance(&createInfo, nullptr, &instance);
    if (createResult != VK_SUCCESS) {
        // Retry without optional extensions before giving up.
        createInfo.enabledExtensionCount = 0;
        createInfo.ppEnabledExtensionNames = nullptr;
        createResult = createInstance(&createInfo, nullptr, &instance);
    }
    if (createResult != VK_SUCCESS) {
        dlclose(loader);
        char buf[128];
        snprintf(buf, sizeof(buf), "vkCreateInstance failed with VkResult %d", createResult);
        return jsonError(buf);
    }

    auto enumeratePhysicalDevices = reinterpret_cast<PFN_vkEnumeratePhysicalDevices>(
            getInstanceProcAddr(instance, "vkEnumeratePhysicalDevices"));
    // vkGetPhysicalDeviceProperties2 / Features2 are core from Vulkan 1.1. On a
    // 1.0 loader they only exist under their VK_KHR_get_physical_device_properties2
    // names, so both spellings are tried.
    auto getPhysicalDeviceProperties2 = reinterpret_cast<PFN_vkGetPhysicalDeviceProperties2>(
            getInstanceProcAddr(instance, "vkGetPhysicalDeviceProperties2"));
    if (getPhysicalDeviceProperties2 == nullptr) {
        getPhysicalDeviceProperties2 = reinterpret_cast<PFN_vkGetPhysicalDeviceProperties2>(
                getInstanceProcAddr(instance, "vkGetPhysicalDeviceProperties2KHR"));
    }
    auto getPhysicalDeviceFeatures2 = reinterpret_cast<PFN_vkGetPhysicalDeviceFeatures2>(
            getInstanceProcAddr(instance, "vkGetPhysicalDeviceFeatures2"));
    if (getPhysicalDeviceFeatures2 == nullptr) {
        getPhysicalDeviceFeatures2 = reinterpret_cast<PFN_vkGetPhysicalDeviceFeatures2>(
                getInstanceProcAddr(instance, "vkGetPhysicalDeviceFeatures2KHR"));
    }
    auto enumerateDeviceExtensionProperties = reinterpret_cast<PFN_vkEnumerateDeviceExtensionProperties>(
            getInstanceProcAddr(instance, "vkEnumerateDeviceExtensionProperties"));

    std::string result = "{\"available\":true,\"source\":\"libcatroid.so dlopen(libvulkan.so)\",";
    result += "\"loaderApiVersion\":" + std::to_string(static_cast<unsigned long long>(loaderApiVersion)) + ",";
    result += "\"instanceExtensions\":" + instanceExtensions + ",";
    result += "\"devices\":[";

    uint32_t deviceCount = 0;
    bool wroteDevice = false;
    if (enumeratePhysicalDevices != nullptr &&
        enumeratePhysicalDevices(instance, &deviceCount, nullptr) == VK_SUCCESS &&
        deviceCount > 0) {

        VkPhysicalDevice* devices = new VkPhysicalDevice[deviceCount];
        if (enumeratePhysicalDevices(instance, &deviceCount, devices) != VK_SUCCESS) {
            deviceCount = 0;
        }

        for (uint32_t i = 0; i < deviceCount; ++i) {
            VkPhysicalDeviceProperties props{};
            auto getProperties = reinterpret_cast<PFN_vkGetPhysicalDeviceProperties>(
                    getInstanceProcAddr(instance, "vkGetPhysicalDeviceProperties"));
            if (getProperties != nullptr) {
                getProperties(devices[i], &props);
            }

            // The device extensions are enumerated first: the feature queries
            // below are only meaningful for the version/extension combination
            // this device actually advertises, so the names drive what is asked.
            std::vector<std::string> deviceExtensions;
            if (enumerateDeviceExtensionProperties != nullptr) {
                uint32_t extCount = 0;
                if (enumerateDeviceExtensionProperties(devices[i], nullptr, &extCount, nullptr) == VK_SUCCESS &&
                    extCount > 0) {
                    VkExtensionProperties* available = new VkExtensionProperties[extCount];
                    if (enumerateDeviceExtensionProperties(devices[i], nullptr, &extCount, available) == VK_SUCCESS) {
                        for (uint32_t e = 0; e < extCount; ++e) {
                            deviceExtensions.emplace_back(available[e].extensionName);
                        }
                    }
                    delete[] available;
                }
            }
            [[maybe_unused]] const auto hasExtension = [&deviceExtensions](const char* name) {
                for (size_t e = 0; e < deviceExtensions.size(); ++e) {
                    if (deviceExtensions[e] == name) {
                        return true;
                    }
                }
                return false;
            };

            FeatureBag features;
            std::string driverName;
            std::string driverInfo;
            int driverId = 0;

            // --- Driver name/info: core in Vulkan 1.2, otherwise the
            // VK_KHR_driver_properties extension provides the same structure.
#if defined(VK_VERSION_1_2)
            if (getPhysicalDeviceProperties2 != nullptr &&
                (props.apiVersion >= VK_API_VERSION_1_2 || hasExtension("VK_KHR_driver_properties"))) {
                VkPhysicalDeviceDriverProperties driverProps{};
                VkPhysicalDeviceProperties2 props2{};
                driverProps.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DRIVER_PROPERTIES;
                props2.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2;
                props2.pNext = &driverProps;
                getPhysicalDeviceProperties2(devices[i], &props2);
                driverId = static_cast<int>(driverProps.driverID);
                driverName = driverProps.driverName;
                driverInfo = driverProps.driverInfo;
            }
#endif

            // --- Descriptor-indexing limits: the numbers VKD3D-Proton depends
            // on (>= 1,000,000 UpdateAfterBind descriptors). Reported only when
            // the device is able to answer the query at all, so "not queried"
            // never looks like "the driver said zero".
#if defined(VK_VERSION_1_2)
            if (getPhysicalDeviceProperties2 != nullptr &&
                (props.apiVersion >= VK_API_VERSION_1_2 || hasExtension("VK_EXT_descriptor_indexing"))) {
                VkPhysicalDeviceDescriptorIndexingProperties indexing{};
                VkPhysicalDeviceProperties2 props2{};
                indexing.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DESCRIPTOR_INDEXING_PROPERTIES;
                props2.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2;
                props2.pNext = &indexing;
                getPhysicalDeviceProperties2(devices[i], &props2);
                features.limit("sampledImageUpdateAfterBind", indexing.maxDescriptorSetUpdateAfterBindSampledImages);
                features.limit("storageImageUpdateAfterBind", indexing.maxDescriptorSetUpdateAfterBindStorageImages);
                features.limit("samplerUpdateAfterBind", indexing.maxDescriptorSetUpdateAfterBindSamplers);
                features.limit("uniformBufferUpdateAfterBind", indexing.maxDescriptorSetUpdateAfterBindUniformBuffers);
            }
#endif

            // --- Core features. The aggregate feature structures
            // (VkPhysicalDeviceVulkan11Features / ...Vulkan12Features) were
            // introduced by the Vulkan 1.2 headers, so the whole block is guarded
            // by VK_VERSION_1_2; the runtime check below then keeps each query
            // valid for the API version the device itself reports.
#if defined(VK_VERSION_1_2)
            if (getPhysicalDeviceFeatures2 != nullptr && props.apiVersion >= VK_API_VERSION_1_1) {
                VkPhysicalDeviceFeatures2 features2{};
                VkPhysicalDeviceVulkan11Features vk11{};
                features2.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2;
                vk11.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_1_FEATURES;
                features2.pNext = &vk11;

                VkPhysicalDeviceVulkan12Features vk12{};
                const bool queryVulkan12 = props.apiVersion >= VK_API_VERSION_1_2;
                if (queryVulkan12) {
                    vk12.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES;
                    vk11.pNext = &vk12;
                }

                getPhysicalDeviceFeatures2(devices[i], &features2);
                features.flag("shaderDrawParameters", vk11.shaderDrawParameters == VK_TRUE);

                if (queryVulkan12) {
                    // samplerMirrorClampToEdge is a Vulkan 1.2 feature. It does
                    // not exist in the Vulkan 1.0 VkPhysicalDeviceFeatures
                    // structure, which is where an earlier revision looked for it.
                    features.flag("samplerMirrorClampToEdge", vk12.samplerMirrorClampToEdge == VK_TRUE);
                    features.flag("descriptorIndexing", vk12.descriptorIndexing == VK_TRUE);
                    features.flag("runtimeDescriptorArray", vk12.runtimeDescriptorArray == VK_TRUE);
                    features.flag("descriptorBindingSampledImageUpdateAfterBind",
                                  vk12.descriptorBindingSampledImageUpdateAfterBind == VK_TRUE);
                    features.flag("descriptorBindingStorageImageUpdateAfterBind",
                                  vk12.descriptorBindingStorageImageUpdateAfterBind == VK_TRUE);
                    features.flag("descriptorBindingUniformBufferUpdateAfterBind",
                                  vk12.descriptorBindingUniformBufferUpdateAfterBind == VK_TRUE);
                    features.flag("timelineSemaphore", vk12.timelineSemaphore == VK_TRUE);
                }
            }
#endif

            // --- graphicsPipelineLibrary lives in VK_EXT_graphics_pipeline_library
            // (it was never promoted into the core 1.3 feature structure), so it
            // may only be queried through that extension's feature struct.
#if defined(VK_EXT_graphics_pipeline_library)
            if (getPhysicalDeviceFeatures2 != nullptr && hasExtension("VK_EXT_graphics_pipeline_library")) {
                VkPhysicalDeviceFeatures2 features2{};
                VkPhysicalDeviceGraphicsPipelineLibraryFeaturesEXT gpl{};
                features2.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2;
                gpl.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_GRAPHICS_PIPELINE_LIBRARY_FEATURES_EXT;
                features2.pNext = &gpl;
                getPhysicalDeviceFeatures2(devices[i], &features2);
                features.flag("graphicsPipelineLibrary", gpl.graphicsPipelineLibrary == VK_TRUE);
            }
#endif

            // --- robustImageAccess2 comes from VK_EXT_robustness2, a
            // VKD3D-Proton requirement that is also not part of the core 1.3
            // feature structure.
#if defined(VK_EXT_robustness2)
            if (getPhysicalDeviceFeatures2 != nullptr && hasExtension("VK_EXT_robustness2")) {
                VkPhysicalDeviceFeatures2 features2{};
                VkPhysicalDeviceRobustness2FeaturesEXT robustness{};
                features2.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2;
                robustness.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ROBUSTNESS_2_FEATURES_EXT;
                features2.pNext = &robustness;
                getPhysicalDeviceFeatures2(devices[i], &features2);
                features.flag("robustImageAccess2", robustness.robustImageAccess2 == VK_TRUE);
                features.flag("nullDescriptor", robustness.nullDescriptor == VK_TRUE);
            }
#endif

            std::string extensions = "[";
            for (size_t e = 0; e < deviceExtensions.size(); ++e) {
                if (e > 0) {
                    extensions += ",";
                }
                extensions += quote(deviceExtensions[e]);
            }
            extensions += "]";

            if (wroteDevice) {
                result += ",";
            }
            wroteDevice = true;

            result += "{";
            result += "\"name\":" + quote(props.deviceName) + ",";
            result += "\"deviceType\":" + std::to_string(static_cast<int>(props.deviceType)) + ",";
            result += "\"deviceTypeName\":" + quote(deviceTypeName(props.deviceType)) + ",";
            result += "\"apiVersion\":" + std::to_string(static_cast<unsigned long long>(props.apiVersion)) + ",";
            result += "\"driverVersion\":" + std::to_string(static_cast<unsigned long long>(props.driverVersion)) + ",";
            result += "\"vendorId\":" + std::to_string(static_cast<int>(props.vendorID)) + ",";
            result += "\"driverId\":" + std::to_string(driverId) + ",";
            result += "\"driverName\":" + quote(driverName) + ",";
            result += "\"driverInfo\":" + quote(driverInfo) + ",";
            result += "\"extensions\":" + extensions + ",";
            result += "\"features\":" + features.toJson();
            result += "}";
        }
        delete[] devices;
    }
    result += "]}";

    auto destroyInstance = reinterpret_cast<PFN_vkDestroyInstance>(
            getInstanceProcAddr(instance, "vkDestroyInstance"));
    if (destroyInstance != nullptr) {
        destroyInstance(instance, nullptr);
    }
    dlclose(loader);
    return result;
}

#endif  // PCRUN_HAS_VULKAN_HEADERS

/*
 * AArch64 feature reporting. This is the in-process half of the native smoke
 * test; the out-of-process half is the `pcrt_probe` helper binary.
 *
 * The capabilities are read from the kernel (AT_HWCAP) rather than from the
 * compiler's feature macros: the NDK compiles for the baseline ABI, so
 * __ARM_FEATURE_CRC32 and __ARM_FEATURE_DOTPROD are 0 even on a Snapdragon
 * 8 Gen 2 whose cores implement both. Reporting "no CRC32" on the chip this
 * work targets would be a false negative, and a false negative here is what
 * makes a device look incapable of running a translated binary.
 */
std::string describeCpuFeatures() {
#if defined(__aarch64__)
    const char* arch = "aarch64";
#elif defined(__arm__)
    const char* arch = "arm";
#elif defined(__x86_64__)
    const char* arch = "x86_64";
#elif defined(__i386__)
    const char* arch = "i386";
#else
    const char* arch = "unknown";
#endif

    // Bit numbers from the kernel UAPI headers:
    //   arch/arm64/include/uapi/asm/hwcap.h and arch/arm/include/uapi/asm/hwcap.h
    const unsigned long hwcapAsimd = 1UL << 1;    // AArch64: Advanced SIMD (NEON)
    const unsigned long hwcapCrc32 = 1UL << 7;    // AArch64: CRC32 instructions
    const unsigned long hwcapAsimddp = 1UL << 20; // AArch64: dot product
    const unsigned long hwcapNeon32 = 1UL << 12;  // AArch32: NEON

    unsigned long hwcap = 0;
#if defined(__aarch64__) || defined(__arm__)
    hwcap = getauxval(AT_HWCAP);
#endif

    const bool haveHwcap = hwcap != 0;
    bool neon;
    bool crc32;
    bool dotprod;
    if (haveHwcap) {
        neon = (hwcap & hwcapAsimd) != 0 || (hwcap & hwcapNeon32) != 0;
        crc32 = (hwcap & hwcapCrc32) != 0;
        dotprod = (hwcap & hwcapAsimddp) != 0;
    } else {
        // getauxval gave no answer (non-ARM build or a very old kernel): fall
        // back to what this translation unit was compiled for.
#if defined(__ARM_NEON) || defined(__ARM_NEON__)
        neon = true;
#else
        neon = false;
#endif
#if defined(__ARM_FEATURE_CRC32)
        crc32 = true;
#else
        crc32 = false;
#endif
#if defined(__ARM_FEATURE_DOTPROD)
        dotprod = true;
#else
        dotprod = false;
#endif
    }

#if defined(__ARM_FEATURE_SVE)
    const bool sve = true;
#else
    const bool sve = false;
#endif

    std::string out = "{";
    out += "\"arch\":" + quote(arch) + ",";
    out += "\"pointerSize\":" + std::to_string(static_cast<int>(sizeof(void*))) + ",";
    out += "\"neon\":" + std::string(neon ? "1" : "0") + ",";
    out += "\"crc32\":" + std::string(crc32 ? "1" : "0") + ",";
    out += "\"dotprod\":" + std::string(dotprod ? "1" : "0") + ",";
    out += "\"sve\":" + std::string(sve ? "1" : "0") + ",";
    out += "\"capabilitiesSource\":" + quote(haveHwcap ? "AT_HWCAP" : "compile-time macros") + ",";
    out += "\"androidApi\":" + std::to_string(__ANDROID_API__);
    out += "}";
    return out;
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_org_catrobat_catroid_pcruntime_VulkanProbe_nativeProbeVulkan(JNIEnv* env, jclass) {
#if PCRUN_HAS_VULKAN_HEADERS
    std::string json;
    try {
        json = probeVulkan();
    } catch (...) {
        json = jsonError("unhandled native exception during the Vulkan probe");
    }
    return env->NewStringUTF(json.c_str());
#else
    (void)env;
    return env->NewStringUTF(
            jsonError("libcatroid.so was built without Vulkan headers; the native probe is unavailable")
                    .c_str());
#endif
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_catrobat_catroid_pcruntime_VulkanProbe_nativeDescribeCpu(JNIEnv* env, jclass) {
    return env->NewStringUTF(describeCpuFeatures().c_str());
}

extern "C" JNIEXPORT jint JNICALL
Java_org_catrobat_catroid_pcruntime_VulkanProbe_nativePcRuntimeApiVersion(JNIEnv*, jclass) {
    return 1;
}
