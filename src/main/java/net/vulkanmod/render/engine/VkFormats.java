package net.vulkanmod.render.engine;

import com.mojang.blaze3d.GpuFormat;

import static org.lwjgl.vulkan.VK10.*;

/** 26.2 port: GpuFormat (replaces GpuFormat / VertexFormatElement.Type) <-> VkFormat. */
public final class VkFormats {
    private VkFormats() {}

    public static int toVk(GpuFormat f) {
        return switch (f) {
            case R8_UNORM -> VK_FORMAT_R8_UNORM;           case R8_SNORM -> VK_FORMAT_R8_SNORM;
            case RG8_UNORM -> VK_FORMAT_R8G8_UNORM;        case RG8_SNORM -> VK_FORMAT_R8G8_SNORM;
            case RGB8_UNORM -> VK_FORMAT_R8G8B8_UNORM;     case RGB8_SNORM -> VK_FORMAT_R8G8B8_SNORM;
            case RGBA8_UNORM -> VK_FORMAT_R8G8B8A8_UNORM;  case RGBA8_SNORM -> VK_FORMAT_R8G8B8A8_SNORM;
            case R16_UNORM -> VK_FORMAT_R16_UNORM;         case R16_SNORM -> VK_FORMAT_R16_SNORM;
            case RG16_UNORM -> VK_FORMAT_R16G16_UNORM;     case RG16_SNORM -> VK_FORMAT_R16G16_SNORM;
            case RGB16_UNORM -> VK_FORMAT_R16G16B16_UNORM; case RGB16_SNORM -> VK_FORMAT_R16G16B16_SNORM;
            case RGBA16_UNORM -> VK_FORMAT_R16G16B16A16_UNORM; case RGBA16_SNORM -> VK_FORMAT_R16G16B16A16_SNORM;
            case R8_UINT -> VK_FORMAT_R8_UINT;             case R8_SINT -> VK_FORMAT_R8_SINT;
            case RG8_UINT -> VK_FORMAT_R8G8_UINT;          case RG8_SINT -> VK_FORMAT_R8G8_SINT;
            case RGB8_UINT -> VK_FORMAT_R8G8B8_UINT;       case RGB8_SINT -> VK_FORMAT_R8G8B8_SINT;
            case RGBA8_UINT -> VK_FORMAT_R8G8B8A8_UINT;    case RGBA8_SINT -> VK_FORMAT_R8G8B8A8_SINT;
            case R16_UINT -> VK_FORMAT_R16_UINT;           case R16_SINT -> VK_FORMAT_R16_SINT;
            case RG16_UINT -> VK_FORMAT_R16G16_UINT;       case RG16_SINT -> VK_FORMAT_R16G16_SINT;
            case RGB16_UINT -> VK_FORMAT_R16G16B16_UINT;   case RGB16_SINT -> VK_FORMAT_R16G16B16_SINT;
            case RGBA16_UINT -> VK_FORMAT_R16G16B16A16_UINT; case RGBA16_SINT -> VK_FORMAT_R16G16B16A16_SINT;
            case R32_UINT -> VK_FORMAT_R32_UINT;           case R32_SINT -> VK_FORMAT_R32_SINT;
            case RG32_UINT -> VK_FORMAT_R32G32_UINT;       case RG32_SINT -> VK_FORMAT_R32G32_SINT;
            case RGB32_UINT -> VK_FORMAT_R32G32B32_UINT;   case RGB32_SINT -> VK_FORMAT_R32G32B32_SINT;
            case RGBA32_UINT -> VK_FORMAT_R32G32B32A32_UINT; case RGBA32_SINT -> VK_FORMAT_R32G32B32A32_SINT;
            case R16_FLOAT -> VK_FORMAT_R16_SFLOAT;        case RG16_FLOAT -> VK_FORMAT_R16G16_SFLOAT;
            case RGB16_FLOAT -> VK_FORMAT_R16G16B16_SFLOAT; case RGBA16_FLOAT -> VK_FORMAT_R16G16B16A16_SFLOAT;
            case R32_FLOAT -> VK_FORMAT_R32_SFLOAT;        case RG32_FLOAT -> VK_FORMAT_R32G32_SFLOAT;
            case RGB32_FLOAT -> VK_FORMAT_R32G32B32_SFLOAT; case RGBA32_FLOAT -> VK_FORMAT_R32G32B32A32_SFLOAT;
            case RGB10A2_UNORM -> VK_FORMAT_A2B10G10R10_UNORM_PACK32;
            case RGB10A2_UINT -> VK_FORMAT_A2B10G10R10_UINT_PACK32;
            case RG11B10_FLOAT -> VK_FORMAT_B10G11R11_UFLOAT_PACK32;
            case D32_FLOAT -> VK_FORMAT_D32_SFLOAT;
            case D32_FLOAT_S8_UINT -> VK_FORMAT_D32_SFLOAT_S8_UINT;
            case D24_UNORM_S8_UINT -> VK_FORMAT_D24_UNORM_S8_UINT;
            case D16_UNORM -> VK_FORMAT_D16_UNORM;
            case S8_UINT -> VK_FORMAT_S8_UINT;
        };
    }

    /** Formats VulkanMod creates images with (colour attachments, atlases, depth). */
    public static GpuFormat fromVk(int vk) {
        return switch (vk) {
            case VK_FORMAT_R8_UNORM -> GpuFormat.R8_UNORM;
            case VK_FORMAT_R8_SINT -> GpuFormat.R8_SINT;
            case VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_B8G8R8A8_UNORM -> GpuFormat.RGBA8_UNORM;
            case VK_FORMAT_R16G16B16A16_SFLOAT -> GpuFormat.RGBA16_FLOAT;
            case VK_FORMAT_D16_UNORM -> GpuFormat.D16_UNORM;
            case VK_FORMAT_X8_D24_UNORM_PACK32, VK_FORMAT_D32_SFLOAT -> GpuFormat.D32_FLOAT;
            case VK_FORMAT_D24_UNORM_S8_UINT -> GpuFormat.D24_UNORM_S8_UINT;
            case VK_FORMAT_D32_SFLOAT_S8_UINT -> GpuFormat.D32_FLOAT_S8_UINT;
            default -> null;
        };
    }
}
