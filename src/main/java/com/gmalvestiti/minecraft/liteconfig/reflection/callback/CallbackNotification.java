package com.gmalvestiti.minecraft.liteconfig.reflection.callback;

record CallbackNotification<T>(T oldState, T newState, boolean fromSync) {}
