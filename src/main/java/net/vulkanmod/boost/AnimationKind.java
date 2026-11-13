package net.vulkanmod.boost;

/** Implemented by SpriteContents.AnimationState (mixin): which texture animation switch applies to the sprite. */
public interface AnimationKind {
   int WATER = 0, LAVA = 1, FIRE = 2, PORTAL = 3, OTHER = 4;

   void vb$setKind(int kind);

   static int of(String spritePath) {
      return spritePath.contains("water") ? WATER : spritePath.contains("lava") ? LAVA : spritePath.contains("fire") ? FIRE
         : spritePath.contains("portal") ? PORTAL : OTHER;
   }
}