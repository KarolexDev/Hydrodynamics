package com.example.exampleplugin.network;

import com.example.exampleplugin.component.ExampleComponent;

public interface BlockNetworkComponent<C extends BlockNetworkComponent<C>> {

    C del(C flux);
    C add(C flux);
    C calculateFlux(C from, C to);
    C[] partition(int left_size, int right_size);
    C zero();
}
