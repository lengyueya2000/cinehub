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

# 同一条理由，但这一批不在 data.model 下面，上面那条盖不到：
# DoubanRepository.Snapshot（豆瓣榜单）和别处几份直接 encodeToString 落盘的快照也是这样，
# 而且它们的必填字段没有默认值 —— 改名之后解不出来，
# 配上 Json { ignoreUnknownKeys = true } 不会抛错，只是静静读成「没有缓存」：
# 升级一次，豆瓣榜和缓存任务清单全部当没存过，用户看到的是「凭空要重新联网拉一遍」。
# 与其数着类名写，不如直接给所有 @Serializable 兜住 —— 以后新加一份快照不会再漏。
-if @kotlinx.serialization.Serializable class **
-keep class <1> { *; }

# ---------------------------------------------------------------------------
# JS 桥
#
# 挂了浏览器验证那道题的源，取数要靠一颗后台 WebView 把 fetch 的结果交回来
# （BrowserEngine）。@JavascriptInterface 是对方靠**方法名**反射调进来的，
# R8 full mode 看不到任何 Kotlin 侧的调用点，会把整条桥当死代码删掉 ——
# 表现是 debug 包一切正常、release 包那个源永远「取不回来」。
# 这条规则默认不在 proguard-android-optimize.txt 里，得自己写。
# ---------------------------------------------------------------------------
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
