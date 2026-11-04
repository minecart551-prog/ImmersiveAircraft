package immersive_aircraft.mixin;

import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reads {@code ServerPlayer#disconnected} - the flag AntiLogout sets via
 * {@code al_onRealDisconnect()} on the ghost it keeps in the world after the client drops.
 *
 * <p>Public alternatives are unreliable: AntiLogout overrides {@code hasDisconnected()} with its
 * own grace-period semantics. The raw field is remapped by the mixin annotation processor, so this
 * works in production without any compile-time dependency on AntiLogout.
 */
@Mixin(ServerPlayer.class)
public interface ServerPlayerDisconnectedAccessor {

    @Accessor("disconnected")
    boolean immersive_aircraft$isDisconnected();
}
