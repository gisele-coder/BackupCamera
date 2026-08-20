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
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        criarCanalNotificacao()
        startForeground(NOTIF_ID, criarNotificacao("Backup iniciando..."))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_LIMPAR -> serviceScope.launch { executarLimpeza() }
            else -> serviceScope.launch { executarBackup() }
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
    private suspend fun executarBackup() {
        try {
            enviarLog("Conectando ao PC na porta $PORT...")
            enviarLog("(Execute 'adb reverse tcp:$PORT tcp:$PORT' no PC)")

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

            // Lista arquivos DCIM
            val dcim = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
            val arquivos = dcim.walkTopDown()
                .filter { it.isFile }
                .filterNot { it.name.startsWith(".trashed") }
                .toList()

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
    private suspend fun executarLimpeza() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                enviarLog("❌ Sem permissão MANAGE_EXTERNAL_STORAGE. Conceda em Configurações.")
                return
            }

            val dcim = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
            var apagados = 0
            var falhas = 0

            dcim.walkTopDown()
                .filter { it.isFile }
                .forEach { arquivo ->
                    try {
                        if (arquivo.delete()) {
                            apagados++
                        } else {
                            falhas++
                            if (falhas <= 10) {
                                enviarLog("⚠️ Falha (delete=false): ${arquivo.absolutePath}")
                            }
                        }
                    } catch (e: Exception) {
                        falhas++
                        if (falhas <= 10) {
                            enviarLog("⚠️ Falha (${e.javaClass.simpleName}): ${arquivo.name} - ${e.message}")
                        }
                    }
                }

            // Remove pastas vazias
            dcim.walkBottomUp()
                .filter { it.isDirectory && it != dcim && it.list().isNullOrEmpty() }
                .forEach { it.delete() }

            enviarLog("✅ Limpeza concluída! $apagados arquivos apagados, $falhas falhas.")
        } catch (e: Exception) {
            enviarLog("❌ Erro na limpeza: ${e.message}")
        } finally {
            stopSelf()
        }
    }

    // ----------------------------------------------------------------
    // Helpers de broadcast
    // ----------------------------------------------------------------
    private fun enviarLog(msg: String) {
        sendBroadcast(Intent(MainActivity.ACTION_UPDATE).apply {
            // Evita lint/segurança com receiver não-exportado no mesmo app.
            setPackage(packageName)
            putExtra(MainActivity.EXTRA_TIPO, MainActivity.TIPO_LOG)
            putExtra(MainActivity.EXTRA_MENSAGEM, msg)
        })
    }

    private fun enviarErro(msg: String) {
        sendBroadcast(Intent(MainActivity.ACTION_UPDATE).apply {
            // Evita lint/segurança com receiver não-exportado no mesmo app.
            setPackage(packageName)
            putExtra(MainActivity.EXTRA_TIPO, MainActivity.TIPO_ERRO)
            putExtra(MainActivity.EXTRA_MENSAGEM, msg)
        })
    }

    private fun enviarConcluido(copiados: Int, ignorados: Int, erros: Int) {
        sendBroadcast(Intent(MainActivity.ACTION_UPDATE).apply {
            // Evita lint/segurança com receiver não-exportado no mesmo app.
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
            // Evita lint/segurança com receiver não-exportado no mesmo app.
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

    // ----------------------------------------------------------------
    // Notificação
    // ----------------------------------------------------------------
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
