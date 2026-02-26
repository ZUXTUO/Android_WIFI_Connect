# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in /home/olsc/AndroidStudioProjects/WIFI/wifi-lib/proguard-android-optimize.txt
# You can edit the include path and usage by changing the ProGuard
# configuration in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Add any project specific keep options here:

# If your project uses WebView with JS, uncomment the following:
# -keepclassmembers class fqcn.of.javascript.interface.for.webview {
#    public *;
# }

# zxing rules might be needed if you enable obfuscation
-keep class com.google.zxing.** { *; }
-keep interface com.google.zxing.** { *; }
-keep class com.journeyapps.barcodescanner.** { *; }
