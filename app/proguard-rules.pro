# R8 规则。目标只有一个：让 release 包在开启混淆后行为和 debug 包一致。

# ---------------------------------------------------------------------------
# kotlinx.serialization
#
# 序列化器是编译期生成在伴生对象上的，R8 在 full mode 下看不到反射式的调用点，
# 会把它们当成无用代码删掉，表现是运行时抛 SerializationException。
# 下面四条是 kotlinx.serialization 官方推荐的 keep 规则。
# ---------------------------------------------------------------------------
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}

-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

-keepattributes RuntimeVisibleAnnotations,AnnotationDefault

# ---------------------------------------------------------------------------
# 持久化模型
#
# SourceConfig 是直接 encodeToString 写进 DataStore 的，属性名就是 JSON 键名。
# 混淆会一致地改名，所以同一个包里读写是自洽的 —— 但那样一来，从 debug 包
# 升级到 release 包时，用户已经存下的播放源会解不出来（代码里有兜底，会退回
# 内置源，但用户自己加的源就丢了）。这几个类的体积可以忽略，保持原样更稳。
# ---------------------------------------------------------------------------
-keep class net.cinehub.data.model.** { *; }
