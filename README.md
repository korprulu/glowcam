# GlowCam 美顏相機

Android 17+ (minSdk 37) 自拍人像美顏相機。功能：即時美顏預覽、磨皮 / 美白 / 紅潤三個強度滑桿、前後鏡頭切換、拍照存入 `Pictures/GlowCam`。

## 演算法
`BeautyFilter`（純 Kotlin）：YCbCr 膚色遮罩 → guided filter 保邊磨皮 → 遮罩加權美白與紅潤。

## 建置與測試
```
./gradlew testDebugUnitTest      # JVM 單元測試
./gradlew assembleRelease        # app/build/outputs/apk/release/app-release.apk
./gradlew connectedDebugAndroidTest  # 需 Android 17 裝置/模擬器
```
需要 JDK 17、Android SDK（platform 37.0、build-tools 37.0.0），並在 `local.properties` 設定 `sdk.dir`。
