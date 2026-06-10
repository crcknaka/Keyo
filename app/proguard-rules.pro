# Keyo R8 rules. The IME service and activity are kept automatically (manifest components);
# Compose, coroutines and OkHttp ship their own consumer rules. Only silence platform warnings.
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
