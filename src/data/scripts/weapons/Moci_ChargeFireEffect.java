package data.scripts.weapons;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.graphics.SpriteAPI;
import org.lwjgl.util.vector.Vector2f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class Moci_ChargeFireEffect implements EveryFrameWeaponEffectPlugin {
    
    private static final String SPRITE_ID = "MSN_04_2_body_beam_fire";
    private static final String SPRITE_CATEGORY = "fx";
    private static final float CHARGE_THRESHOLD = 0.8f;
    private static final float MAX_SIZE_MULTIPLIER = 4f;
    private static final float GROW_DURATION = 0.1f;
    private static final float SHRINK_DURATION = 0.2f;
    private static final CombatEngineLayers RENDER_LAYER = CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER;
    private static final float SPRITE_WIDTH = 120f;
    private static final float SPRITE_HEIGHT = 138f;
    
    private boolean renderPluginInitialized = false;
    
    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        if (!renderPluginInitialized) {
            renderPluginInitialized = true;
            engine.addLayeredRenderingPlugin(new ChargeFireRenderPlugin(weapon));
        }
    }
    
    private static class ChargeFireRenderPlugin extends BaseCombatLayeredRenderingPlugin {
        private final ShipAPI ship;
        private final WeaponAPI weapon;
        private final SpriteAPI sprite;
        private boolean isExpired = false;
        private final List<EffectObject> effects = new ArrayList<>();
        private boolean lastFiring = false;
        private boolean hasTriggered = false;
        
        private ChargeFireRenderPlugin(WeaponAPI weapon) {
            this.ship = weapon.getShip();
            this.weapon = weapon;
            this.layer = RENDER_LAYER;
            this.sprite = Global.getSettings().getSprite(SPRITE_CATEGORY, SPRITE_ID);
        }
        
        @Override
        public float getRenderRadius() {
            return Math.max(SPRITE_WIDTH, SPRITE_HEIGHT) * MAX_SIZE_MULTIPLIER * 2f;
        }
        
        @Override
        public void advance(float amount) {
            this.entity.getLocation().set(weapon.getFirePoint(0));
            
            CombatEngineAPI engine = Global.getCombatEngine();
            if (engine.isPaused()) {
                return;
            }
            
            Iterator<EffectObject> iter = effects.iterator();
            while (iter.hasNext()) {
                EffectObject effect = iter.next();
                effect.advance(amount);
                if (effect.shouldRemove()) {
                    iter.remove();
                }
            }
            
            if (!ship.isAlive()) {
                if (effects.isEmpty()) {
                    isExpired = true;
                }
                return;
            }
            
            float chargeLevel = weapon.getChargeLevel();
            boolean isFiring = weapon.isFiring();
            
            if (!isFiring && lastFiring) {
                hasTriggered = false;
            }
            
            if (chargeLevel >= CHARGE_THRESHOLD && isFiring && !hasTriggered) {
                effects.add(new EffectObject(sprite));
                hasTriggered = true;
            }
            
            lastFiring = isFiring;
        }
        
        @Override
        public void render(CombatEngineLayers layer, ViewportAPI viewport) {
            if (this.layer == layer) {
                Vector2f point = weapon.getFirePoint(0);
                float x = point.x;
                float y = point.y;
                float angle = weapon.getCurrAngle();
                
                for (EffectObject effect : effects) {
                    effect.render(x, y, angle);
                }
            }
        }
        
        @Override
        public boolean isExpired() {
            return isExpired;
        }
    }
    
    private static class EffectObject {
        private final SpriteAPI sprite;
        private float life;
        private final float totalLife;
        
        private EffectObject(SpriteAPI sprite) {
            this.sprite = sprite;
            this.totalLife = GROW_DURATION + SHRINK_DURATION;
            this.life = totalLife;
        }
        
        public void advance(float amount) {
            if (life > 0) {
                life -= amount;
                if (life < 0) {
                    life = 0;
                }
            }
        }
        
        public void render(float x, float y, float angle) {
            if (life > 0) {
                float scale;
                float elapsed = totalLife - life;
                
                if (elapsed < GROW_DURATION) {
                    scale = (elapsed / GROW_DURATION) * MAX_SIZE_MULTIPLIER;
                } else {
                    float shrinkProgress = (elapsed - GROW_DURATION) / SHRINK_DURATION;
                    scale = MAX_SIZE_MULTIPLIER * (1f - shrinkProgress);
                }
                
                float width = SPRITE_WIDTH * scale;
                float height = SPRITE_HEIGHT * scale;
                
                float offsetDistance = height / 2f;
                float renderX = x + (float) Math.cos(Math.toRadians(angle)) * offsetDistance;
                float renderY = y + (float) Math.sin(Math.toRadians(angle)) * offsetDistance;
                
                sprite.setWidth(width);
                sprite.setHeight(height);
                sprite.setAngle(angle - 90f);
                sprite.renderAtCenter(renderX, renderY);
            }
        }
        
        public boolean shouldRemove() {
            return life <= 0;
        }
    }
}
