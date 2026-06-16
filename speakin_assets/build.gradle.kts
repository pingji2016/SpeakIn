plugins {
    id("com.android.asset-pack")
}

assetPack {
    packName.set("speakin_assets")
    dynamicDelivery {
        deliveryType.set("install-time")
    }
}
