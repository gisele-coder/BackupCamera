package com.backup.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.net.Socket
import java.net.SocketTimeoutException

class BackupService : Service() {

    companion object {
        const val ACTION_LIMPAR = "com.backup.android.LIMPAR"
        const val HOST = "127.0.0.1"   // via ADB reverse tunnel
        const val PORT = 9999
        const val CHANNEL_ID = "backup_channel"
        const val NOTIF_ID = 1

        const val EXTRA_MODE = "extra_mode"
        const val MODE_CAMERA = "mode_camera"
        const val MODE_OTHERS = "mode_others"
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        criarCanalNotificacao()
        startForeground(NOTIF_ID, criarNotificacao("Backup iniciando..."))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val mode = intent?.getStringExtra(EXTRA_MODE) ?: MODE_CAMERA
        when (intent?.action) {
            ACTION_LIMPAR -> serviceScope.launch { executarLimpeza(mode) }
            else -> serviceScope.launch { executarBackup(mode) }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    // ----------------------------------------------------------------
    // BACKUP
    // ----------------------------------------------------------------
    private suspend fun executarBackup(mode: String) {
        try {
            val pastasAlvo = if (mode == MODE_CAMERA) {
                listOf(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM))
            } else {
                obterPastasOutros()
            }

            enviarLog("🔍 Escaneando arquivos ($mode)...")
            
            val arquivos = mutableListOf<File>()
            pastasAlvo.forEach { pasta ->
                if (pasta.exists()) {
                    arquivos.addAll(pasta.walkTopDown()
                        .filter { it.isFile && !it.name.contains(".trashed") && !it.name.startsWith(".") }
                        .toList())
                }
            }

            if (arquivos.isEmpty()) {
                enviarLog("⚠️ Nenhum arquivo encontrado para backup.")
                stopSelf()
                return
            }

            enviarLog("Conectando ao PC na porta $PORT...")

            val socket: Socket
            try {
                socket = Socket(HOST, PORT)
                socket.soTimeout = 30_000
            } catch (e: Exception) {
                enviarErro("Não foi possível conectar ao PC. Verifique se o servidor está rodando. (${e.message})")
                stopSelf()
                return
            }

            val out = DataOutputStream(socket.getOutputStream())
            enviarLog("✅ Conectado ao PC!")

            val total = arquivos.size
            enviarLog("📁 Total de arquivos: $total")

            // Envia total ao PC
            out.writeInt(total)
            out.flush()

            var copiados = 0
            var ignorados = 0
            var erros = 0
            val inicio = System.currentTimeMillis()

            arquivos.forEachIndexed { index, arquivo ->
                val caminhoRelativo = arquivo.relativeTo(
                    Environment.getExternalStorageDirectory()
                ).path

                // Envia metadados: caminho + tamanho
                val caminhoBytes = caminhoRelativo.toByteArray(Charsets.UTF_8)
                out.writeInt(caminhoBytes.size)
                out.write(caminhoBytes)
                out.writeLong(arquivo.length())
                out.flush()

                // Lê resposta do PC: 0=precisa copiar, 1=já existe
                val resposta = socket.getInputStream().read()

                if (resposta == 1) {
                    ignorados++
                    enviarProgressoELog(
                        index + 1, total, arquivo.name,
                        copiados, ignorados, erros, inicio,
                        "JA EXISTE  $caminhoRelativo"
                    )
                } else {
                    // Envia o arquivo
                    try {
                        FileInputStream(arquivo).use { fis ->
                            val buffer = ByteArray(64 * 1024) // 64KB chunks
                            var bytesLidos: Int
                            while (fis.read(buffer).also { bytesLidos = it } != -1) {
                                out.write(buffer, 0, bytesLidos)
                            }
                        }
                        out.flush()

                        // Lê confirmação do PC
                        val confirmacao = socket.getInputStream().read()
                        if (confirmacao == 1) {
                            copiados++
                            enviarProgressoELog(
                                index + 1, total, arquivo.name,
                                copiados, ignorados, erros, inicio,
                                "OK         $caminhoRelativo"
                            )
                        } else {
                            erros++
                            enviarProgressoELog(
                                index + 1, total, arquivo.name,
                                copiados, ignorados, erros, inicio,
                                "FALHOU     $caminhoRelativo"
                            )
                        }
                    } catch (e: Exception) {
                        erros++
                        enviarProgressoELog(
                            index + 1, total, arquivo.name,
                            copiados, ignorados, erros, inicio,
                            "ERRO       $caminhoRelativo: ${e.message}"
                        )
                    }
                }

                atualizarNotificacao("${index + 1}/$total - ${arquivo.name}")
            }

            // Sinaliza fim
            out.writeInt(-1)
            out.flush()
            socket.close()

            enviarConcluido(copiados, ignorados, erros)

        } catch (e: Exception) {
            enviarErro("Erro inesperado: ${e.message}")
        } finally {
            stopSelf()
        }
    }

    // ----------------------------------------------------------------
    // LIMPEZA
    // ----------------------------------------------------------------
    private suspend fun executarLimpeza(mode: String) {
        try {
            val pastasAlvo = if (mode == MODE_CAMERA) {
                listOf(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM))
            } else {
                obterPastasOutros()
            }

            enviarLog("🔍 Escaneando arquivos para limpeza ($mode)...")
            
            val arquivos = mutableListOf<File>()
            pastasAlvo.forEach { pasta ->
                if (pasta.exists()) {
                    arquivos.addAll(pasta.walkTopDown()
                        .filter { it.isFile }
                        .toList())
                }
            }
            
            val total = arquivos.size
            var apagados = 0
            var falhas = 0

            enviarLog("🗑 Apagando $total arquivos...")
            val inicio = System.currentTimeMillis()

            arquivos.forEachIndexed { index, arquivo ->
                try {
                    if (arquivo.delete()) {
                        apagados++
                    } else {
                        falhas++
                    }
                } catch (e: Exception) {
                    falhas++
                }
                
                // Atualiza progresso e ETA para limpeza também
                if ((index + 1) % 10 == 0 || index + 1 == total) {
                    enviarProgressoELog(
                        index + 1, total, arquivo.name,
                        apagados, 0, falhas, inicio,
                        "LIMPANDO: ${arquivo.name}"
                    )
                }
            }

            // Remove pastas vazias (opcional)
            pastasAlvo.forEach { pasta ->
                if (pasta.exists()) {
                    pasta.walkBottomUp()
                        .filter { it.isDirectory && it != pasta }
                        .forEach { it.delete() }
                }
            }

            enviarLog("✅ Limpeza concluída!")
            enviarLog("📊 Resultado: $apagados apagados, $falhas falhas.")
            
        } catch (e: Exception) {
            enviarLog("❌ Erro fatal na limpeza: ${e.message}")
        } finally {
            stopSelf()
        }
    }

    private fun obterPastasOutros(): List<File> {
        val storage = Environment.getExternalStorageDirectory()
        val paths = listOf(
            "WhatsApp/Media",
            "Android/media/com.whatsapp/WhatsApp/Media",
            "Android/media/com.whatsapp.w4b/WhatsApp Business/Media",
            "Telegram",
            "Pictures/Instagram",
            "Pictures/Telegram",
            "Pictures/Facebook",
            "Pictures/Twitter",
            "Download",
            "Movies/Instagram",
            "Movies/Snapchat"
        )
        return paths.map { File(storage, it) }.filter { it.exists() }
    }

    // ----------------------------------------------------------------
    // Helpers de broadcast
    // ----------------------------------------------------------------
    private fun enviarLog(msg: String) {
        sendBroadcast(Intent(MainActivity.ACTION_UPDATE).apply {
            setPackage(packageName)
            putExtra(MainActivity.EXTRA_TIPO, MainActivity.TIPO_LOG)
            putExtra(MainActivity.EXTRA_MENSAGEM, msg)
        })
    }

    private fun enviarErro(msg: String) {
        sendBroadcast(Intent(MainActivity.ACTION_UPDATE).apply {
            setPackage(packageName)
            putExtra(MainActivity.EXTRA_TIPO, MainActivity.TIPO_ERRO)
            putExtra(MainActivity.EXTRA_MENSAGEM, msg)
        })
    }

    private fun enviarConcluido(copiados: Int, ignorados: Int, erros: Int) {
        sendBroadcast(Intent(MainActivity.ACTION_UPDATE).apply {
            setPackage(packageName)
            putExtra(MainActivity.EXTRA_TIPO, MainActivity.TIPO_CONCLUIDO)
            putExtra(MainActivity.EXTRA_COPIADOS, copiados)
            putExtra(MainActivity.EXTRA_IGNORADOS, ignorados)
            putExtra(MainActivity.EXTRA_ERROS, erros)
        })
    }

    private fun enviarProgressoELog(
        atual: Int, total: Int, arquivo: String,
        copiados: Int, ignorados: Int, erros: Int,
        inicio: Long, logMsg: String
    ) {
        val progresso = ((atual.toFloat() / total) * 100).toInt()
        val decorrido = System.currentTimeMillis() - inicio
        val eta = if (atual > 1) {
            val segsRestantes = ((decorrido / atual.toFloat()) * (total - atual) / 1000).toLong()
            "%02dh %02dm %02ds".format(segsRestantes / 3600, (segsRestantes % 3600) / 60, segsRestantes % 60)
        } else "calculando..."

        sendBroadcast(Intent(MainActivity.ACTION_UPDATE).apply {
            setPackage(packageName)
            putExtra(MainActivity.EXTRA_TIPO, MainActivity.TIPO_PROGRESSO)
            putExtra(MainActivity.EXTRA_PROGRESSO, progresso)
            putExtra(MainActivity.EXTRA_TOTAL, total)
            putExtra(MainActivity.EXTRA_ATUAL, arquivo)
            putExtra(MainActivity.EXTRA_COPIADOS, copiados)
            putExtra(MainActivity.EXTRA_IGNORADOS, ignorados)
            putExtra(MainActivity.EXTRA_ERROS, erros)
            putExtra(MainActivity.EXTRA_ETA, eta)
        })

        enviarLog(logMsg)
    }

    private fun criarCanalNotificacao() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val canal = NotificationChannel(
                CHANNEL_ID, "Backup Câmera",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Progresso do backup" }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(canal)
        }
    }

    private fun criarNotificacao(texto: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Backup Câmera")
            .setContentText(texto)
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setOngoing(true)
            .build()

    private fun atualizarNotificacao(texto: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, criarNotificacao(texto))
    }
}
