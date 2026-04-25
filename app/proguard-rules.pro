# ==============================================================================
# PulseMusic - Profesyonel ProGuard / R8 Yapılandırması
# ==============================================================================

# 1. TEMEL ANDROID VE DEBUG KURALLARI
# ------------------------------------------------------------------------------
-keepattributes SourceFile,LineNumberTable,Signature,EnclosingMethod,InnerClasses,*Annotation*,Exception
-dontwarn java.lang.invoke.*
-dontwarn **$$Lambda$*
-dontwarn javax.annotation.**

# 2. GOOGLE CAST (CHROMECAST) - KRİTİK ÇÖZÜM
# ------------------------------------------------------------------------------
# Cast framework'ün yansıma (reflection) ile sınıf bulmasını sağlar
-keep class com.google.android.gms.cast.framework.** { *; }
-dontwarn com.google.android.gms.cast.framework.**
-keep class * implements com.google.android.gms.cast.framework.OptionsProvider { *; }

# Kendi Cast sağlayıcını ismen koru (Hata buradaydı)
-keep class code.name.monkey.pulsemusic.cast.CastOptionsProvider { *; }

# 3. APACHE COMMONS COMPRESS (ZIP/RAR HATASI ÇÖZÜMÜ)
# ------------------------------------------------------------------------------
-keep class org.apache.commons.compress.** { *; }
-dontwarn org.apache.commons.compress.**

# 4. PULSEMUSIC ÖZEL MODELLER VE NETWORK
# ------------------------------------------------------------------------------
-keep class code.name.monkey.pulsemusic.network.model.** { *; }
-keep class code.name.monkey.pulsemusic.model.** { *; }
-keep class code.name.monkey.pulsemusic.db.** { *; }

# 5. RETROFIT & OKHTTP & GSON
# ------------------------------------------------------------------------------
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }
-keepattributes Signature, InnerClasses, EnclosingMethod
-keep interface retrofit2.Call
-keep class retrofit2.Response
-keep class kotlin.coroutines.Continuation

-keepattributes *Annotation*
-keep interface com.squareup.okhttp3.** { *; }
-dontwarn com.squareup.okhttp3.**

-keep class com.google.gson.reflect.TypeToken
-keep class * extends com.google.gson.reflect.TypeToken
-keep public class * implements java.lang.reflect.Type

# 6. GLIDE (GÖRSEL YÜKLEME)
# ------------------------------------------------------------------------------
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule { <init>(...); }
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** {
  **[] $VALUES;
  public *;
}
-keep class com.bumptech.glide.load.data.ParcelFileDescriptorRewinder$InternalRewinder {
  *** rewind();
}

# 7. JAUDIOTAGGER & JCODEC (SES ETİKETLERİ)
# ------------------------------------------------------------------------------
-dontwarn org.jaudiotagger.**
-dontwarn org.jcodec.**
-keep class org.jaudiotagger.** { *; }
-keep class org.jcodec.** { *; }

# 8. ANDROIDX & UI KURALLARI
# ------------------------------------------------------------------------------
-keepnames class androidx.navigation.fragment.NavHostFragment
-keep class * extends androidx.fragment.app.Fragment { *; }
-keep class com.google.android.material.bottomsheet.** { *; }
-keepclassmembers enum * { *; }

# 9. SERIALIZABLE & PARCELABLE KORUMASI
# ------------------------------------------------------------------------------
-keepnames class * extends android.os.Parcelable
-keepnames class * implements java.io.Serializable
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}