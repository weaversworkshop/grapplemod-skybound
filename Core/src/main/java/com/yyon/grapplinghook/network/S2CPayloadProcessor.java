package com.yyon.grapplinghook.network;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/**
 * @deprecated Replaced by {@code com.yyon.grapplinghook.client.network.ClientNetworkReceivers},
 * which keeps all client-only handler code off the server classpath. This
 * interface remains only so external references don't break immediately;
 * nothing in-tree implements it any longer.
 */
@Deprecated
public interface S2CPayloadProcessor {

    @Environment(EnvType.CLIENT)
    void process(ClientPlayNetworking.Context ctx);

}
