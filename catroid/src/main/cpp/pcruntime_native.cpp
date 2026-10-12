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
 * Collects the device features that the documented upstream requirements for
 * DXVK/VKD3D-Proton actually depend on, and the descriptor-indexing limits
 * VKD3D-Proton needs (>= 1,000,000 UpdateAfterBind descriptors).
 */
std::string collectFeatures(const VkPhysicalDeviceFeatures2& core,
                            const VkPhysicalDeviceVulkan11Features& vk11,
                            const VkPhysicalDeviceVulkan12Features& vk12,
                            const VkPhysicalDeviceDescriptorIndexingProperties& indexingProps) {
    // Emits a flat "name":value map so the Kotlin side can look capabilities up
    // by name without a schema. Booleans are emitted as 0/1, limits as counts.
    std::string out = "[";
    bool first = true;
    auto add = [&](const char* name, bool value) {
        if (!first) out += ",";
        first = false;
        out += quote(name);
        if (value) {
            out += ",1";
        } else {
            out += ",0";
        }
    };
    auto addValue = [&](const char* name, unsigned long long value) {
        if (!first) out += ",";
        first = false;
        out += quote(name);
        out += ",";
        out += std::to_string(value);
    };

    add("shaderDrawParameters", vk11.shaderDrawParameters == VK_TRUE);
    add("samplerMirrorClampToEdge", core.features.samplerMirrorClampToEdge == VK_TRUE);
    add("descriptorIndexing", vk12.descriptorIndexing == VK_TRUE);
    add("runtimeDescriptorArray", vk12.runtimeDescriptorArray == VK_TRUE);
    add("descriptorBindingSampledImageUpdateAfterBind",
        vk12.descriptorBindingSampledImageUpdateAfterBind == VK_TRUE);
    add("descriptorBindingStorageImageUpdateAfterBind",
        vk12.descriptorBindingStorageImageUpdateAfterBind == VK_TRUE);
    add("robustImageAccess2", core.features.robustImageAccess2 == VK_TRUE);
    add("timelineSemaphore", vk12.timelineSemaphore == VK_TRUE);

    addValue("sampledImageUpdateAfterBind",
             indexingProps.maxDescriptorSetUpdateAfterBindSampledImages);
    addValue("storageImageUpdateAfterBind",
             indexingProps.maxDescriptorSetUpdateAfterBindStorageImages);
    addValue("samplerUpdateAfterBind",
             indexingProps.maxDescriptorSetUpdateAfterBindSamplers);
    addValue("uniformBufferUpdateAfterBind",
             indexingProps.maxDescriptorSetUpdateAfterBindUniformBuffers);

    out += "]";
    return out;
}

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
    if (VK_API_VERSION_MAJOR(loaderApiVersion) == 1 && VK_API_VERSION_MINOR(loaderApiVersion) == 0 &&
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
    auto getPhysicalDeviceProperties2 = reinterpret_cast<PFN_vkGetPhysicalDeviceProperties2>(
            getInstanceProcAddr(instance, "vkGetPhysicalDeviceProperties2"));
    auto getPhysicalDeviceFeatures2 = reinterpret_cast<PFN_vkGetPhysicalDeviceFeatures2>(
            getInstanceProcAddr(instance, "vkGetPhysicalDeviceFeatures2"));
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
            VkPhysicalDeviceDriverProperties driverProps{};
            VkPhysicalDeviceDescriptorIndexingProperties indexingProps{};
            VkPhysicalDeviceVulkan11Features vk11{};
            VkPhysicalDeviceVulkan12Features vk12{};
            VkPhysicalDeviceVulkan13Features vk13{};
            VkPhysicalDeviceFeatures2 features2{};

            bool haveDriverProps = false;
            bool haveIndexingProps = false;
            bool haveFeatures2 = false;

            // Core 1.0 query always succeeds, so it is the baseline.
            auto getProperties = reinterpret_cast<PFN_vkGetPhysicalDeviceProperties>(
                    getInstanceProcAddr(instance, "vkGetPhysicalDeviceProperties"));
            if (getProperties != nullptr) {
                getProperties(devices[i], &props);
            }

            if (getPhysicalDeviceProperties2 != nullptr) {
                VkPhysicalDeviceProperties2 props2{};
                props2.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2;
                driverProps.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DRIVER_PROPERTIES;
                indexingProps.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DESCRIPTOR_INDEXING_PROPERTIES;

                // Per the Vulkan specification an implementation must ignore
                // unrecognised sTypes in a query pNext chain, so chaining the
                // newer structs is safe even on an older driver.
                props2.pNext = &driverProps;
                driverProps.pNext = &indexingProps;
                getPhysicalDeviceProperties2(devices[i], &props2);
                haveDriverProps = true;
                haveIndexingProps = true;
            }

            if (getPhysicalDeviceFeatures2 != nullptr) {
                features2.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2;
                vk11.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_1_FEATURES;
                vk12.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES;
                vk13.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES;
                vk11.pNext = &vk12;
                vk12.pNext = &vk13;
                features2.pNext = &vk11;
                getPhysicalDeviceFeatures2(devices[i], &features2);
                haveFeatures2 = true;
            }

            std::string extensions = "[";
            bool firstDeviceExt = true;
            if (enumerateDeviceExtensionProperties != nullptr) {
                uint32_t extCount = 0;
                if (enumerateDeviceExtensionProperties(devices[i], nullptr, &extCount, nullptr) == VK_SUCCESS &&
                    extCount > 0) {
                    VkExtensionProperties* available = new VkExtensionProperties[extCount];
                    if (enumerateDeviceExtensionProperties(devices[i], nullptr, &extCount, available) == VK_SUCCESS) {
                        for (uint32_t e = 0; e < extCount; ++e) {
                            if (!firstDeviceExt) extensions += ",";
                            firstDeviceExt = false;
                            extensions += quote(available[e].extensionName);
                        }
                    }
                    delete[] available;
                }
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
            result += "\"driverId\":";
            result += haveDriverProps ? std::to_string(static_cast<int>(driverProps.driverID)) : std::string("0");
            result += ",\"driverName\":";
            result += haveDriverProps ? quote(driverProps.driverName) : quote("");
            result += ",\"driverInfo\":";
            result += haveDriverProps ? quote(driverProps.driverInfo) : quote("");
            result += ",\"extensions\":" + extensions + ",";
            result += "\"features\":";
            if (haveFeatures2) {
                VkPhysicalDeviceDescriptorIndexingProperties propsForFeatures{};
                if (haveIndexingProps) {
                    propsForFeatures = indexingProps;
                }
                result += collectFeatures(features2, vk11, vk12, propsForFeatures);
            } else {
                result += "[]";
            }
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

/**
 * AArch64 feature reporting. This is the in-process half of the native smoke
 * test; the out-of-process half is the `pcrt_probe` helper binary.
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

    std::string out = "{";
    out += "\"arch\":" + quote(arch) + ",";
    out += "\"pointerSize\":" + std::to_string(static_cast<int>(sizeof(void*))) + ",";
#if defined(__ARM_FEATURE_CRC32)
    out += "\"crc32\":1,";
#else
    out += "\"crc32\":0,";
#endif
#if defined(__ARM_NEON) || defined(__ARM_NEON__)
    out += "\"neon\":1,";
#else
    out += "\"neon\":0,";
#endif
#if defined(__ARM_FEATURE_DOTPROD)
    out += "\"dotprod\":1,";
#else
    out += "\"dotprod\":0,";
#endif
#if defined(__ARM_FEATURE_SVE)
    out += "\"sve\":1,";
#else
    out += "\"sve\":0,";
#endif
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
