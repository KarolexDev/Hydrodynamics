package com.example.exampleplugin.Resource;

import com.example.exampleplugin.component.ExampleComponent;
import com.example.exampleplugin.network.BlockNetwork;
import com.hypixel.hytale.component.Resource;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.jspecify.annotations.Nullable;

public class ExampleNetworkResource extends BlockNetwork<ExampleComponent> implements Resource<EntityStore> {

    @Override
    public @Nullable Resource<EntityStore> clone() {
        return null;
    }

    @Override
    protected String fromConnectionMaskToBlockID(byte bits) {
        return null;
    }

    @Override
    protected void updateConnectionOnCoords(World world, byte bits) {
//        command.run(
//                (store) -> {
//                    WorldChunk wc = store.getComponent(stateInfo.getChunkRef(), WorldChunk.getComponentType());
//                    wc.setBlockInteractionState(
//                            currentBlockCoords.x,
//                            currentBlockCoords.y,
//                            currentBlockCoords.z,
//                            wc.getBlockType(currentBlockCoords),
//                            state,
//                            true
//                    );
//                }
//        );
    }


}
