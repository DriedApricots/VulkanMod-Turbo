package net.vulkanmod.mixin.boost;

import java.util.function.Supplier;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.resources.model.cuboid.ItemTransform;
import org.joml.Matrix4f;
import org.joml.Vector3fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ItemStackRenderState.LayerRenderState.class)
public interface ItemLayerAccessor {
   @Accessor("extents")
   Supplier<Vector3fc[]> vb$extents();

   @Accessor("itemTransform")
   ItemTransform vb$itemTransform();

   @Accessor("localTransform")
   Matrix4f vb$localTransform();
}