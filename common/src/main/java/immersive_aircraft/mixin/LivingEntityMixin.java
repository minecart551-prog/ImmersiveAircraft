package immersive_aircraft.mixin;

import immersive_aircraft.entity.VehicleEntity;
import immersive_aircraft.entity.VehiclePersistence;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Fixes players taking lethal fall damage right after leaving an aircraft.
 * <p>
 * {@link LivingEntity#dismountVehicle(Entity)} runs server side only and ends by calling
 * {@code dismountTo(x, y, z)}. For a {@link ServerPlayer} that is a bare
 * {@code removeVehicle() + setPos()} - no fall distance reset and, crucially, no position
 * packet for the client.
 * <p>
 * The client therefore keeps believing it is still a passenger, so {@code LocalPlayer#sendPosition()}
 * keeps streaming the <em>aircraft's</em> position while the server has already moved the player to
 * {@link VehicleEntity#getDismountLocationForPassenger(LivingEntity)}. {@code handleMovePlayer} trusts those
 * packets and drags the server player along with the aircraft ({@code absMoveTo(packetPos)}). By the
 * time the client finally learns about the dismount (via {@code ClientboundSetPassengersPacket}) and
 * snaps back, the server measures a fall equal to however far the aircraft travelled in the meantime -
 * which for a climbing aircraft is easily enough to kill outright.
 * <p>
 * Forcing a real teleport on dismount closes that window: both sides now agree on where the player
 * is, so no phantom fall is ever accumulated.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {

    @Inject(method = "dismountVehicle", at = @At("TAIL"))
    private void immersive_aircraft$resyncAfterVehicleDismount(Entity vehicle, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;

        if (!(vehicle instanceof VehicleEntity)) {
            return;
        }

        // Passengers never accumulate fall distance while riding (rideTick() -> resetFallDistance()),
        // so whatever the server has at this point is stale bookkeeping, not a real fall.
        self.resetFallDistance();

        if (self instanceof ServerPlayer player) {
            player.connection.teleport(
                    player.getX(), player.getY(), player.getZ(),
                    player.getYRot(), player.getXRot()
            );
        }
    }

    /**
     * Never let the rejoin itself be lethal.
     *
     * <p>If the aircraft a player left could not be found again (destroyed while they were gone, or
     * sitting in an unloaded chunk) they reappear at their old altitude with no mount. That fall is
     * a side effect of logging out, not something the player chose to do, so it must not kill them.
     */
    @Inject(method = "causeFallDamage", at = @At("HEAD"), cancellable = true)
    private void immersive_aircraft$ignoreFallJustAfterRejoin(float fallDistance, float multiplier, DamageSource source, CallbackInfoReturnable<Boolean> cir) {
        LivingEntity self = (LivingEntity) (Object) this;

        if (self instanceof ServerPlayer player && VehiclePersistence.inJoinGrace(player)) {
            cir.setReturnValue(false);
        }
    }
}
