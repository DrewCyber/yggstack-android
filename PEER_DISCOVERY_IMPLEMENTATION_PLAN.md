# План реализации функции Peer Discovery для Yggstack Android

**Дата создания:** 2 января 2026  
**Версия:** 1.0

---

## Общая концепция

Реализация автоматического обнаружения и выбора оптимальных публичных пиров Yggdrasil с тремя режимами работы:
- **Auto** - полностью автоматический выбор и подключение к лучшему пиру
- **Manual** - предоставление пользователю отсортированного списка для выбора
- **Custom** - текущий функционал (ручной ввод пиров)

---

## Ответы на технические вопросы

### 1. Mobile bindings и проверка доступности
- ✅ Ping - реализуется в Kotlin через `InetAddress.isReachable()` или системный ping
- ✅ TCP/TLS - Socket и SSLSocket API
- ⚠️ QUIC - требует уточнения (Cronet библиотека, skip, или Go bindings)
- ✅ WS/WSS - OkHttp WebSocket
- **Приоритет протоколов:** TCP > TLS > QUIC > WS > WSS

### 2. External IP определение
- Использовать публичные сервисы (api.ipify.org, icanhazip.com и др.)
- IPv4 в приоритете
- IPv6 только если IPv4 недоступен

### 3. Хранение данных
- Room Database для структурированного хранения
- Миграция старых конфигов не требуется

### 4. UI/UX
- Auto режим: текущий пир виден в Diagnostics → Config
- Local Link: отдельная статичная секция
- Выгрузка кэша не требуется

### 5. Производительность
- Лимит одновременных проверок: 10 потоков
- Таймауты: 5 сек для ping, 10 сек для connect
- Фоновые задачи через WorkManager

### 6. Дополнительная информация из JSON
- **Страна:** название из ключа верхнего уровня (например, `armenia.md`)
- **Метаданные пира:** `up`, `response_ms`, `key`, `last_seen`
- Использовать для предварительной фильтрации и отображения

---

## Архитектура решения

### Структура компонентов

```
app/src/main/java/link/yggdrasil/yggstack/android/
├── data/
│   ├── peer/
│   │   ├── PublicPeer.kt                 # Модель публичного пира
│   │   ├── PeerProtocol.kt               # Enum протоколов
│   │   ├── PeerSelectionMode.kt          # Enum режимов выбора
│   │   ├── SortingType.kt                # Enum типов сортировки
│   │   ├── PeerCacheMetadata.kt          # Метаданные кэша
│   │   ├── PeerDatabase.kt               # Room Database
│   │   ├── PeerDao.kt                    # DAO интерфейсы
│   │   └── PeerRepository.kt             # Repository паттерн
│   │
├── service/
│   ├── discovery/
│   │   ├── PeerDiscoveryService.kt       # Загрузка публичных списков
│   │   ├── PeerCacheManager.kt           # Управление кэшем
│   │   └── ExternalIpDetector.kt         # Определение external IP
│   │
│   ├── availability/
│   │   ├── NetworkCheckService.kt        # Базовые сетевые проверки
│   │   ├── PingChecker.kt                # Ping проверки
│   │   ├── TcpConnectChecker.kt          # TCP connect проверки
│   │   ├── TlsConnectChecker.kt          # TLS connect проверки
│   │   ├── QuicConnectChecker.kt         # QUIC connect проверки
│   │   ├── WebSocketChecker.kt           # WS/WSS проверки
│   │   └── PeerAvailabilityChecker.kt    # Координатор проверок
│   │
│   ├── sorting/
│   │   ├── PeerSortingService.kt         # Сортировка пиров
│   │   └── PeerFilterService.kt          # Фильтрация по протоколам
│   │
│   └── selection/
│       ├── AutoPeerSelector.kt           # Автовыбор пиров
│       └── PeerSelectionStrategy.kt      # Стратегии выбора
│
├── ui/
│   ├── peers/
│   │   ├── PeerDiscoveryScreen.kt        # UI списка пиров
│   │   ├── PeerDiscoveryViewModel.kt     # ViewModel
│   │   ├── PeerListItem.kt               # Компонент элемента списка
│   │   └── PeerSortingProgressDialog.kt  # Диалог прогресса
│   │
│   └── configuration/
│       └── ConfigurationScreen.kt        # Обновленный экран (переключатель режимов)
│
└── worker/
    ├── PeerCacheUpdateWorker.kt          # Периодическое обновление кэша
    └── PeerSortingWorker.kt              # Фоновая сортировка
```

---

## Этапы реализации

### **Этап 1: Инфраструктура и модели данных** (2-3 дня)

#### 1.1 Создание моделей данных

**Файл:** `PublicPeer.kt`
```kotlin
@Entity(tableName = "peers_cache")
data class PublicPeer(
    @PrimaryKey val uri: String,
    val protocol: PeerProtocol,
    val host: String,
    val port: Int,
    val country: String,
    val pingMs: Long?,
    val connectRtt: Long?,
    val lastChecked: Long?,
    val addedAt: Long = System.currentTimeMillis(),
    val isManuallyAdded: Boolean = false
)
```

**Файл:** `PeerProtocol.kt`
```kotlin
enum class PeerProtocol {
    TCP, TLS, QUIC, WS, WSS;
    
    fun getPriority(): Int = when (this) {
        TCP -> 1
        TLS -> 2
        QUIC -> 3
        WS -> 4
        WSS -> 5
    }
}
```

**Файл:** `PeerSelectionMode.kt`
```kotlin
enum class PeerSelectionMode {
    AUTO,    // Полностью автоматический выбор
    MANUAL,  // Выбор из отсортированного списка
    CUSTOM   // Ручной ввод (текущий функционал)
}
```

**Файл:** `SortingType.kt`
```kotlin
enum class SortingType {
    NONE,     // Без сортировки
    PING,     // Сортировка по ping
    CONNECT,  // Сортировка по protocol connect
    METADATA  // Предварительная сортировка по response_ms из JSON
}
```

**Файл:** `PeerCacheMetadata.kt`
```kotlin
@Entity(tableName = "peer_cache_metadata")
data class PeerCacheMetadata(
    @PrimaryKey val externalIp: String,
    val sortingType: SortingType,
    val sortedAt: Long,
    val totalPeersChecked: Int,
    val successfulPeersCount: Int
)
```

**Файл:** `SortedPeerList.kt`
```kotlin
@Entity(tableName = "sorted_peer_lists")
data class SortedPeerList(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val externalIp: String,
    val peerUri: String,
    val sortIndex: Int,
    val sortingType: SortingType,
    val sortedAt: Long
)
```

#### 1.2 Room Database

**Файл:** `PeerDatabase.kt`
```kotlin
@Database(
    entities = [
        PublicPeer::class,
        PeerCacheMetadata::class,
        SortedPeerList::class
    ],
    version = 1,
    exportSchema = false
)
abstract class PeerDatabase : RoomDatabase() {
    abstract fun peerDao(): PeerDao
    abstract fun sortedListDao(): SortedListDao
    
    companion object {
        @Volatile
        private var INSTANCE: PeerDatabase? = null
        
        fun getDatabase(context: Context): PeerDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    PeerDatabase::class.java,
                    "peer_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
```

**Файл:** `PeerDao.kt`
```kotlin
@Dao
interface PeerDao {
    @Query("SELECT * FROM peers_cache ORDER BY addedAt DESC")
    fun getAllPeers(): Flow<List<PublicPeer>>
    
    @Query("SELECT * FROM peers_cache WHERE protocol IN (:protocols)")
    fun getPeersByProtocol(protocols: List<PeerProtocol>): Flow<List<PublicPeer>>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPeers(peers: List<PublicPeer>)
    
    @Query("DELETE FROM peers_cache WHERE isManuallyAdded = 0")
    suspend fun clearNonManualPeers()
    
    @Query("DELETE FROM peers_cache")
    suspend fun clearAllPeers()
    
    @Update
    suspend fun updatePeer(peer: PublicPeer)
}
```

#### 1.3 Обновление YggstackConfig

Добавить в `YggstackConfig.kt`:
```kotlin
data class YggstackConfig(
    // ... существующие поля
    val peerSelectionMode: PeerSelectionMode = PeerSelectionMode.CUSTOM,
    val selectedPeerUris: List<String> = emptyList(), // для Manual режима
    val lastAutoSelectedPeer: String? = null // для Auto режима
)
```

---

### **Этап 2: Go Mobile Bindings для QUIC** (1 день)

#### 3.0 Добавление метода проверки QUIC

**Файл:** `lib/yggstack/mobile/yggstack.go`

Добавить метод для проверки QUIC соединения:

```go
// CheckQuicConnect проверяет QUIC подключение к хосту и возвращает RTT в миллисекундах
// Возвращает -1 в случае ошибки
func CheckQuicConnect(host string, port int64, timeoutMs int64) int64 {
    ctx, cancel := context.WithTimeout(context.Background(), time.Duration(timeoutMs)*time.Millisecond)
    defer cancel()
    
    addr := fmt.Sprintf("%s:%d", host, port)
    startTime := time.Now()
    
    // Создаем QUIC конфигурацию
    tlsConf := &tls.Config{
        InsecureSkipVerify: true, // Для проверки доступности игнорируем валидацию сертификата
        NextProtos:         []string{"yggdrasil"},
    }
    
    quicConf := &quic.Config{
        HandshakeIdleTimeout: time.Duration(timeoutMs) * time.Millisecond,
    }
    
    // Пытаемся установить QUIC соединение
    conn, err := quic.DialAddr(ctx, addr, tlsConf, quicConf)
    if err != nil {
        return -1
    }
    defer conn.CloseWithError(0, "")
    
    // Измеряем время установки соединения
    rtt := time.Since(startTime).Milliseconds()
    return rtt
}
```

После изменений пересобрать библиотеку:
```bash
cd lib/yggstack
./build-android.sh
cp android-build/yggstack.aar ../../app/libs/
```

---

### **Этап 3: Сетевые операции и логика обнаружения** (3-4 дня)

#### 2.1 Сервис загрузки публичных списков

**Файл:** `PeerDiscoveryService.kt`
```kotlin
class PeerDiscoveryService(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    
    private val sources = listOf(
        "https://publicpeers.neilalexander.dev/publicnodes.json",
        "https://peers.yggdrasil.link/publicnodes.json"
    )
    
    suspend fun fetchPublicPeers(): Result<List<PublicPeer>> = withContext(Dispatchers.IO) {
        for (source in sources) {
            try {
                val request = Request.Builder().url(source).build()
                val response = client.newCall(request).execute()
                
                if (response.isSuccessful) {
                    val json = response.body?.string() ?: continue
                    val peers = parsePublicNodesJson(json)
                    return@withContext Result.success(peers)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch from $source", e)
                continue
            }
        }
        Result.failure(Exception("All sources failed"))
    }
    
    private fun parsePublicNodesJson(json: String): List<PublicPeer> {
        val peers = mutableListOf<PublicPeer>()
        val rootObject = JSONObject(json)
        
        rootObject.keys().forEach { countryKey ->
            // Убираем расширение .md и капитализируем первую букву
            val country = countryKey.removeSuffix(".md")
                .replaceFirstChar { it.uppercase() }
            val countryObject = rootObject.getJSONObject(countryKey)
            
            countryObject.keys().forEach { uri ->
                val protocol = extractProtocol(uri)
                val (host, port) = extractHostPort(uri)
                
                // Игнорируем метаданные из JSON (up, response_ms, key, last_seen)
                // так как они относятся к другому хосту
                peers.add(PublicPeer(
                    uri = uri,
                    protocol = protocol,
                    host = host,
                    port = port,
                    country = country,
                    pingMs = null,
                    connectRtt = null,
                    lastChecked = null
                ))
            }
        }
        
        return peers
    }
    
    private fun extractProtocol(uri: String): PeerProtocol {
        return when {
            uri.startsWith("tcp://") -> PeerProtocol.TCP
            uri.startsWith("tls://") -> PeerProtocol.TLS
            uri.startsWith("quic://") -> PeerProtocol.QUIC
            uri.startsWith("ws://") -> PeerProtocol.WS
            uri.startsWith("wss://") -> PeerProtocol.WSS
            else -> PeerProtocol.TCP
        }
    }
    
    private fun extractHostPort(uri: String): Pair<String, Int> {
        val withoutProtocol = uri.substringAfter("://")
        val parts = withoutProtocol.split(":")
        val host = parts[0]
        val port = parts.getOrNull(1)?.toIntOrNull() ?: 0
        return host to port
    }
}
```

#### 2.2 Определение External IP

**Файл:** `ExternalIpDetector.kt`
```kotlin
class ExternalIpDetector {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()
    
    private val ipv4Sources = listOf(
        "https://api.ipify.org",
        "https://icanhazip.com",
        "https://ifconfig.me/ip"
    )
    
    private val ipv6Sources = listOf(
        "https://api6.ipify.org",
        "https://icanhazip.com"
    )
    3
    suspend fun detectExternalIp(): String? = withContext(Dispatchers.IO) {
        // Пытаемся получить IPv4
        for (source in ipv4Sources) {
            try {
                val ip = fetchIp(source)
                if (ip != null && isValidIpv4(ip)) {
                    return@withContext ip
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get IPv4 from $source", e)
            }
        }
        
        // Если IPv4 недоступен, пытаемся IPv6
        for (source in ipv6Sources) {
            try {
                val ip = fetchIp(source)
                if (ip != null && isValidIpv6(ip)) {
                    return@withContext ip
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get IPv6 from $source", e)
            }
        }
        
        null
    }
    
    private fun fetchIp(url: String): String? {
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()
        return response.body?.string()?.trim()
    }
    
    private fun isValidIpv4(ip: String): Boolean {
        return Patterns.IP_ADDRESS.matcher(ip).matches()
    }
    
    private fun isValidIpv6(ip: String): Boolean {
        return try {
            InetAddress.getByName(ip) is Inet6Address
        } catch (e: Exception) {
            false
        }
    }
}
```

#### 2.3 Проверка доступности пиров

**Файл:** `PingChecker.kt`
```kotlin
class PingChecker {
    suspend fun checkPing(host: String, timeoutMs: Int = 5000): Long? = withContext(Dispatchers.IO) {
        try {
            val address = InetAddress.getByName(host)
            val startTime = System.currentTimeMillis()
            val isReachable = address.isReachable(timeoutMs)
            val endTime = System.currentTimeMillis()
            
            if (isReachable) {
                endTime - startTime
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ping failed for $host", e)
            null
        }
    }
}
```

**Файл:** `TcpConnectChecker.kt`
```kotlin
class TcpConnectChecker {
    suspend fun checkConnect(host: String, port: Int, timeoutMs: Int = 10000): Long? = 
        withContext(Dispatchers.IO) {
            try {
                val address = InetSocketAddress(host, port)
                val socket = Socket()
                val startTime = System.currentTimeMillis()
                
                socket.connect(address, timeoutMs)
                val endTime = System.currentTimeMillis()
                
                socket.close()
                endTime - startTime
            } catch (e: Exception) {
                Log.e(TAG, "TCP connect failed for $host:$port", e)
                null
            }
        }
}
```

**Файл:** `TlsConnectChecker.kt`
```kotlin
class TlsConnectChecker {
    suspend fun checkConnect(host: String, port: Int, timeoutMs: Int = 10000): Long? = 
        withContext(Dispatchers.IO) {
            try {
                val socketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
                val startTime = System.currentTimeMillis()
                
                val socket = socketFactory.createSocket()
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                
                val endTime = System.currentTimeMillis()
                socket.close()
                
                endTime - startTime
            } catch (e: Exception) {
                Log.e(TAG, "TLS connect failed for $host:$port", e)
                null
            }
        }
}
```

**Файл:** `WebSocketChecker.kt`
```kotlin
class WebSocketChecker {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()
    
    suspend fun checkConnect(uri: String, timeoutMs: Int = 10000): Long? = 
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(uri).build()
                val startTime = System.currentTimeMillis()
                
                var connected = false
                val latch = CountDownLatch(1)
                
                val webSocket = client.newWebSocket(request, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        connected = true
                        latch.countDown()
                        webSocket.close(1000, "Check complete")
                    }
                    
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        latch.countDown()
                    }
                })
                
                latch.await(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                
                if (connected) {
                    val endTime = System.currentTimeMillis()
                    endTime - startTime
                } else {
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "WebSocket connect failed for $uri", e)
                null
     3      }
        }
}
```

**Файл:** `PeerAvailabilityChecker.kt`
```kotlin
class PeerAvailabilityChecker(
    private val pingChecker: PingChecker,
    private val tcpChecker: TcpConnectChecker,
    privateQuicConnectChecker.kt`
```kotlin
class QuicConnectChecker(private val yggstack: Yggstack) {
    suspend fun checkConnect(host: String, port: Int, timeoutMs: Int = 10000): Long? = 
        withContext(Dispatchers.IO) {
            try {
                // Используем Go mobile bindings для проверки QUIC
                yggstack.checkQuicConnect(host, port.toLong(), timeoutMs.toLong())
            } catch (e: Exception) {
                Log.e(TAG, "QUIC connect failed for $host:$port", e)
                null
            }
        }
}
```

**Файл:** `PeerAvailabilityChecker.kt`
```kotlin
class PeerAvailabilityChecker(
    private val pingChecker: PingChecker,
    private val tcpChecker: TcpConnectChecker,
    private val tlsChecker: TlsConnectChecker,
    private val quicChecker: QuicConnectChecker,
    private val webSocketChecker: WebSocketChecker
) {
    private val semaphore = Semaphore(10) // Лимит одновременных проверок
    
    suspend fun checkPeersPing(peers: List<PublicPeer>): List<PublicPeer> {
        return peers.map { peer ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    val pingTime = pingChecker.checkPing(peer.host)
                    peer.copy(
                        pingMs = pingTime,
                        lastChecked = System.currentTimeMillis()
                    )
                }
            }
        }.awaitAll()
    }
    
    suspend fun checkPeersConnect(peers: List<PublicPeer>): List<PublicPeer> {
        return peers.map { peer ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    val rtt = when (peer.protocol) {
                        PeerProtocol.TCP -> tcpChecker.checkConnect(peer.host, peer.port)
                        PeerProtocol.TLS -> tlsChecker.checkConnect(peer.host, peer.port)
                        PeerProtocol.QUIC -> quicChecker.checkConnect(peer.host, peer.port)
                    )
                }
            }
        }.awaitAll()
    }
}
```4: Логика автоматического выбора пиров** (2-3 дня)

#### 4.4 Сортировка пиров

**Файл:** `PeerSortingService.kt`
```kotlin
class PeerSortingService(
    private val availabilityChecker: PeerAvailabilityChecker,
    private val peerDao: PeerDao,
    private val sortedListDao: SortedListDao
) {
    suspend fun sortByPing(
        externalIp: String,
        peers: List<PublicPeer>,
        onProgress: (Int, Int) -> Unit
    ): List<PublicPeer> {
        val checked = availabilityChecker.checkPeersPing(peers)
        peerDao.insertPeers(checked)
        
        val sorted = checked
            .filter { it.pingMs != null }
            .sortedWith(compareBy({ it.pingMs }, { it.protocol.getPriority() }))
        
        saveSortedList(externalIp, sorted, SortingType.PING)
        return sorted
    }
    
    suspend fun sortByConnect(
        externalIp: String,
        peers: List<PublicPeer>,
        onProgress: (Int, Int) -> Unit
    ): List<PublicPeer> {
        val checked = availabilityChecker.checkPeersConnect(peers)
        peerDao.insertPeers(checked)
        
        val sorted = checked
            .filter { it.connectRtt != null }
            .sortedWith(compareBy({ it.connectRtt }, { it.protocol.getPriority() }))
        
        saveSortedList(externalIp, sorted, SortingType.CONNECT)
        return sorted
    }
    
    private suspend fun saveSortedList(
        externalIp: String,
        peers: List<PublicPeer>,
        sortingType: SortingType
    ) {
        sortedListDao.deleteSortedListForIp(externalIp)
        
        val sortedList = peers.mapIndexed { index, peer ->
            SortedPeerList(
                externalIp = externalIp,
                peerUri = peer.uri,
                sortIndex = index,
                sortingType = sortingType,
     4          sortedAt = System.currentTimeMillis()
            )
        }
        
        sortedListDao.insertSortedList(sortedList)
    }
}
```

---

### **Этап 3: Логика автоматического выбора пиров** (2-3 дня)

#### 3.1 Auto режим

**Файл:** `AutoPeerSelector.kt`
```kotlin
class AutoPeerSelector(
    private val peerDao: PeerDao,
    private val sortedListDao: SortedListDao,
    private val sortingService: PeerSortingService,
    private val externalIpDetector: ExternalIpDetector,
    private val configRepository: ConfigRepository
) {
    suspend fun selectBestPeer(): String? {
        val externalIp = externalIpDetector.detectExternalIp() ?: return null
        
        // Проверяем наличие актуального сортированного списка
        val sortedList = sortedListDao.getSortedListForIp(externalIp)
        
        if (sortedList.isEmpty()) {
            // Нет списка - быстрая сортировка по ping
            val allPeers = peerDao.getAllPeers().first()
            val sorted = sortingService.sortByPing(externalIp, allPeers) { _, _ -> }
            
            // Запускаем фоновую сортировку по connect
            launchBackgroundConnectSort(externalIp, allPeers)
            
            return sorted.firstOrNull()?.uri
     4  }
        
        // Возвращаем лучший пир из списка с приоритетом TCP
        return sortedList
            .sortedBy { it.sortIndex }
            .firstOrNull()?.peerUri
    }
    
    private fun launchBackgroundConnectSort(externalIp: String, peers: List<PublicPeer>) {
        CoroutineScope(Dispatchers.IO).launch {
            val sorted = sortingService.sortByConnect(externalIp, peers) { _, _ -> }
            val bestPeer = sorted.firstOrNull()?.uri
            
            if (bestPeer != null) {
                // Обновляем конфиг с новым пиром
                updateConfigWithPeer(bestPeer)
            }
        }
    }
    
    private suspend fun updateConfigWithPeer(peerUri: String) {
        val config = configRepository.getConfig().first()
        val updatedPeers = listOf(peerUri) + config.peers.filter { it != peerUri }
        configRepository.saveConfig(config.copy(peers = updatedPeers))
    }
}
```

#### 3.2 Интеграция с YggstackService

Обновить [YggstackService.kt](YggstackService.kt):
```kotlin
class YggstackService : Service() {
    // ... существующий код
    
    private lateinit var autoPeerSelector: AutoPeerSelector
    private var lastExternalIp: String? = null
    
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            serviceScope.launch {
                handleNetworkChange()
            }
        }
    }
    
    private suspend fun handleNetworkChange() {
        val config = repository.getConfig().first()
        
        if (config.peerSelectionMode == PeerSelectionMode.AUTO) {
            val currentIp = externalIpDetector.detectExternalIp()
            
            if (currentIp != null && currentIp != lastExternalIp) {
                lastExternalIp = currentIp
                
                // Выбираем лучший пир для нового IP
                val bestPeer = autoPeerSelector.selectBestPeer()
                if (bestPeer != null) {
                    // Обновляем конфиг и переподключаемся
                    updateConfigAndReconnect(bestPeer)
                }
            }
        }
    }
}
```5: UI для управления пирами** (3-4 дня)

#### 5.3 WorkManager задачи

**Файл:** `PeerCacheUpdateWorker.kt`
```kotlin
class PeerCacheUpdateWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    
    override suspend fun doWork(): Result {
        val discoveryService = PeerDiscoveryService(applicationContext)
        val peerDao = PeerDatabase.getDatabase(applicationContext).peerDao()
        
        return try {
            val result = discoveryService.fetchPublicPeers()
            
            result.fold(
                onSuccess = { peers ->
                    peerDao.insertPeers(peers)
                    Result.success()
                },
                onFailure = {
                    Result.retry()
                }
            )
        } catch (e: Exception) {
            Result.failure()
        }
    }
    
    companion object {
        fun schedulePeriodicUpdate(context: Context) {
            val request = PeriodicWorkRequestBuilder<PeerCacheUpdateWorker>(
                24, TimeUnit.HOURS
            ).build()
            
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "peer_cache_update",
                ExistingPeriodicWorkPolicy.KEEP,
                request
        
        // Local Link секция (внизу карточки PEERS)
        if (currentMode != PeerSelectionMode.AUTO) {
            Spacer(modifier = Modifier.height(16.dp))
     5      LocalLinkPeersSection(
                config = config,
                onToggleMulticastBeacon = { viewModel.updateMulticastBeacon(it) },
                onToggleMulticastListen = { viewModel.updateMulticastListen(it) }
            )
        }
    }
}

@Composable
fun LocalLinkPeersSection(
    config: YggstackConfig,
    onToggleMulticastBeacon: (Boolean) -> Unit,
    onToggleMulticastListen: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                "Local Link Peers (Multicast)",
                style = MaterialTheme.typography.titleMedium
            )
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = config.multicastBeacon,
                    onCheckedChange = onToggleMulticastBeacon
                )
                Text("Multicast Beacon")
            }
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = config.multicastListen,
                    onCheckedChange = onToggleMulticastListen
                )
                Text("Multicast Listen")
            }
        }
            )
        }
    }
}
```

---

### **Этап 4: UI для управления пирами** (3-4 дня)

#### 4.1 Обновление ConfigurationScreen

Добавить переключатель режимов:
```kotlin
@Composable
fun PeerSelectionModeSelector(
    currentMode: PeerSelectionMode,
    onModeChanged: (PeerSelectionMode) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text("Peer Selection Mode", style = MaterialTheme.typography.titleMedium)
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            PeerSelectionMode.values().forEach { mode ->
                FilterChip(
                    selected = mode == currentMode,
                    onClick = { onModeChanged(mode) },
                    label = { Text(mode.name) }
                )
            }
        }
        
        when (currentMode) {
            PeerSelectionMode.AUTO -> {
                Text("Automatic peer selection enabled", 
                     style = MaterialTheme.typography.bodySmall)
            }
            PeerSelectionMode.MANUAL -> {
                Button(onClick = { /* Navigate to PeerDiscoveryScreen */ }) {
                    Text("Open Peer List")
                }
            }
            PeerSelectionMode.CUSTOM -> {
                // Существующий UI для ручного ввода
            }
        }
    }
}
```

#### 4.2 Новый экран PeerDiscoveryScreen

**Файл:** `PeerDiscoveryScreen.kt`
```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeerDiscoveryScreen(
    viewModel: PeerDiscoveryViewModel,
    onNavigateBack: () -> Unit
) {
    val peers by viewModel.peers.collectAsState()
    val externalIp by viewModel.externalIp.collectAsState()
    val sortingInProgress by viewModel.sortingInProgress.collectAsState()
    val sortingProgress by viewModel.sortingProgress.collectAsState()
    val selectedProtocols by viewModel.selectedProtocols.collectAsState()
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Column {
                        Text("Public Peers")
                        externalIp?.let {
                            Text("External IP: $it", 
                                 style = MaterialTheme.typography.bodySmall)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            // Кнопки управления
            PeerManagementButtons(
                onClearCache = { viewModel.clearCache() },
                onDownloadList = { viewModel.downloadPeerList() },
                onSortByPing = { viewModel.sortByPing() },
                onSortByConnect = { viewModel.sortByConnect() }
            )
            
            // Фильтры по протоколам
            ProtocolFilterRow(
                selectedProtocols = selectedProtocols,
                onProtocolToggle = { viewModel.toggleProtocolFilter(it) }
            )
            
            // Список пиров
            if (sortingInProgress) {
                LinearProgressIndicator(
                    progress = sortingProgress,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            
            LazyColumn {
                items(peers) { peer ->
                    PeerListItem(
                        peer = peer,
                        isSelected = viewModel.isPeerSelected(peer.uri),
                        onToggleSelection = { viewModel.togglePeerSelection(peer.uri) }
                    )
                }
                
                item {
                    ManualPeerAddField(
                        onAddPeer = { viewModel.addManualPeer(it) }
                    )
                }
            }
        }
    }
}
```

**Файл:** `PeerListItem.kt`
```kotlin
@Composable
fun PeerListItem(
    peer: PublicPeer,
    isSelected: Boolean,
    onToggleSelection: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding( доступности (если был проверен)
            if (peer.connectRtt != null || peer.pingMs != null) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = "Checked",
                    tint = MaterialTheme.colorScheme.primary
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onToggleSelection() }
            )
            
            Spacer(modifier = Modifier.width(8.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                // Страна и протокол
                Row {
                    Text(
                        text = peer.country,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Badge { Text(peer.protocol.name) }
                }
                
                // URI
                Text(
                    text = peer.uri,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                
                // RTT информация
                Row {
                    peer.pingMs?.let {
                        Text("Ping: ${it}ms", 
                             style = MaterialTheme.typography.bodySmall)
                    }
                    peer.connectRtt?.let {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("RTT: ${it}ms", 
                             style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            
            // Статус
            if (peer.isUp) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = "Online",
                    tint = Color.Green
                )
            }
        }
    }
}
```

#### 4.3 ViewModel

**Файл:** `PeerDiscoveryViewModel.kt`
```kotlin
class PeerDiscoveryViewModel(
    private val peerRepository: PeerRepository,
    private val sortingService: PeerSortingService,
    private val discoveryService: PeerDiscoveryService,
    private val externalIpDetector: ExternalIpDetector,
    private val configRepository: ConfigRepository
) : ViewModel() {
    
    private val _peers = MutableStateFlow<List<PublicPeer>>(emptyList())
    val peers: StateFlow<List<PublicPeer>> = _peers.asStateFlow()
    
    private val _externalIp = MutableStateFlow<String?>(null)
    val externalIp: StateFlow<String?> = _externalIp.asStateFlow()
    
    private val _sortingInProgress = MutableStateFlow(false)
    val sortingInProgress: StateFlow<Boolean> = _sortingInProgress.asStateFlow()
    
    private val _sortingProgress = MutableStateFlow(0f)
    val sortingProgress: StateFlow<Float> = _sortingProgress.asStateFlow()
    
    private val _selectedProtocols = MutableStateFlow(PeerProtocol.values().toList())
    val selectedProtocols: StateFlow<List<PeerProtocol>> = _selectedProtocols.asStateFlow()
    
    private val selectedPeerUris = mutableSetOf<String>()
    
    init {
        loadPeers()
        detectExternalIp()
    }
    
    private fun loadPeers() {
        viewModelScope.launch {
            peerRepository.getAllPeers()
                .combine(selectedProtocols) { peers, protocols ->
                    peers.filter { it.protocol in protocols }
           6: Тестирование и оптимизация** (2-3 дня)

#### 6  }
    }
    
    private fun detectExternalIp() {
        viewModelScope.launch {
            _externalIp.value = externalIpDetector.detectExternalIp()
     6  }
    }
    
    fun downloadPeerList() {
        viewModelScope.launch {
     6      val result = discoveryService.fetchPublicPeers()
            result.onSuccess { peers ->
                peerRepository.insertPeers(peers)
            }
        }
    }6
    
    fun sortByPing() {
        viewModelScope.launch {
            _sortingInProgress.value = true
            val ip = _externalIp.value ?: return@launch
            
            sortingService.sortByPing(ip, _peers.value) { current, total ->
                _sortingProgress.value = current.toFloat() / total
            }
            
            _sortingInProgress.value = false
        }
    }
    
    fun sortByConnect() {
        viewModelScope.launch {
            _sortingInProgress.value = true
            val ip = _externalIp.value ?: return@launch
            
            sortingService.sortByConnect(ip, _peers.value) { current, total ->
                _sortingProgress.value = current.toFloat() / total
            }
            
            _sortingInProgress.value = false
        }
    }
    
    fun togglePeerSelection(uri: String) {
        if (selectedPeerUris.contains(uri)) {
            selectedPeerUris.remove(uri)
        } else {
            selectedPeerUris.add(uri)
        }
        
        // Сразу обновляем конфиг
        updateConfigWithSelectedPeers()
    }
    
    fun isPeerSelected(uri: String): Boolean = selectedPeerUris.contains(uri)
    
    fun toggleProtocolFilter(protocol: PeerProtocol) {
        val current = _selectedProtocols.value.toMutableList()
        if (current.contains(protocol)) {
            current.remove(protocol)
        } else {
            current.add(protocol)
        }
        _selectedProtocols.value = current
    }
    
    fun clearCache() {
        viewModelScope.launch {
            peerRepository.clearNonManualPeers()
        }
    }
    
    fun addManualPeer(uri: String) {
        viewModelScope.launch {
            // Парсим URI и добавляем как ручной пир
            val peer = parseManualPeer(uri)
            peerRepository.insertPeer(peer.copy(isManuallyAdded = true))
        }
    }
    
    private fun updateConfigWithSelectedPeers() {
        viewModelScope.launch {
            val config = configRepository.getConfig().first()
            configRepository.saveConfig(
                config.copy(
                    peers = selectedPeerUris.toList(),
                    peerSelectionMode = PeerSelectionMode.MANUAL
                )
            )
        }
    }
}
```

---

### **Этап 5: Тестирование и оптимизация** (2-3 дня)

#### 5.1 Unit тесты
- Тесты для парсинга JSON
- Тесты логики сортировки
- Тесты выбора пиров в Auto режиме
- Тесты фильтрации по протоколам

#### 5.2 Интеграционные тесты
- Тест полного цикла: загрузка → сортировка → выбор
- Тест смены сети в Auto режиме
- Тест WorkManager задач

#### 5.3 UI тесты
- Тест переключения режимов
- Тест сортировки списка
- Тест выбора пиров

#### 5.4 Оптимизация
- Профилирование производительности проверок доступности
- Оптимизация количества одновременных проверок
- Кэширование DNS резолюции
- Оптимизация запросов к БД (индексы)

---

## Зависимости для добавления
встроенный org.json используется
    // Room Database
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")
    
    // WorkManager
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    
    // OkHttp для HTTP запросов и WebSocket
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    
    // JSON парсинг (если не используется встроенный)
    implementation("org.json:json:20231013")
    
    // Для QUIC (опционально, если решим поддержать)
    // implementation("com.google.android.gms:play-services-cronet:18.0.1")
}
```

---
Go Mobile Bindings для QUIC | 1 день |
| Этап 3 | Сетевые операции и логика обнаружения | 3-4 дня |
| Этап 4 | Логика автоматического выбора пиров | 2-3 дня |
| Этап 5 | UI для управления пирами | 3-4 дня |
| Этап 6 | Тестирование и оптимизация | 2-3 дня |
| **ИТОГО** | | **13-18етод проверки QUIC в Go mobile bindings

Необходимо реализовать в `lib/yggstack/mobile/yggstack.go`:
```go
// CheckQuicConnect проверяет QUIC подключение к указанному хосту
func CheckQuicConnect(host string, port int, timeoutMs int) (int64, error) {
    // Реализация QUIC connect с измерением RTT
}
```

### ✅ Отображение стран
**Решение:** Только текст без флагов, убрать расширение `.md`

Пример отображения:
- `armenia.md` → `Armenia` (capitalize first letter)
- `australia.md` → `Australia`

**Сортировка:** Только по RTT (без группировки по странам)

### ✅ Использование метаданных из JSON
**Решение:** Игнорировать метаданные (`up`, `response_ms`, `last_seen`)

Причина: Метаданные относятся к другому хосту, не актуальны для текущего устройства.
Все проверки выполняются самостоятельно.

### ✅ Статистика кэша и уведомления
**Решение:** Не показывать статистику кэша и не отправлять уведомления

Кэш обновляется незаметно в фоне, без UI индикации.

### ✅ Local Link секция
**Решение:** Внизу карточки PEERS на Configuration экране

- Отдельная подсекция "Local Link Peers (Multicast)"
- Только галочки для включения/выключения
- Без проверки доступности
- Статичная секция (не участвует в peer discovery)

---

## Оценка трудозатрат

| Этап | Описание | Время |
|------|----------|-------|
| Этап 1 | Инфраструктура и модели данных | 2-3 дня |
| Этап 2 | Сетевые операции и логика обнаружения | 3-4 дня |
| Этап 3 | Логика автоматического выбора пиров | 2-3 дня |
| Этап 4 | UI для управления пирами | 3-4 дня |
| Этап 5 | Тестирование и оптимизация | 2-3 дня |
| **ИТОГО** | | **12-17 дней** |

---

## Следующие шаги

1. ✅ Согласовать план реализации
2. ✅ Ответить на открытые вопросы
3. ⏳ Начать реализацию Этапа 1
4. ⏳ Создать feature branch: `feature/peer-discovery`
5. ⏳ Добавить зависимости в `build.gradle.kts`

---

## Примечания

- План может корректироваться в процессе реализации
- Приоритет на стабильность и производительность
- Код должен быть покрыт тестами минимум на 70%
- Документация и комментарии обязательны для публичных API

---

**Автор:** GitHub Copilot  
**Последнее обновление:** 2 января 2026
