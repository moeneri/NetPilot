# app_process loads this entry point by its binary name from the signed APK.
-keep class dev.netpilot.service.RootSupervisor.RootSupervisorMain { public static void main(java.lang.String[]); }
# Framework callbacks use runtime interface names and dynamic proxies.
-keep class dev.netpilot.compat.** { *; }
-keep class dev.netpilot.service.RootSupervisor.** { *; }
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*
-dontwarn org.slf4j.**
