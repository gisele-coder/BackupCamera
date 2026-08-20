package com.backup.android

import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.ProgressBar
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var btnBackup: Button
    private lateinit var btnLimpar: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvPercent: TextView
    private lateinit var tvArquivoAtual: TextView
    private lateinit var tvContador: TextView
    private lateinit var tvEta: TextView
    private lateinit var tvLog: TextView
    private lateinit var tvCopiados: TextView
    private lateinit var tvIgnorados: TextView
    private lateinit var tvErros: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var scrollLog: ScrollView
    private lateinit var bottomNavigation: BottomNavigationView
    
    private lateinit var tvIconHeader: TextView
    private lateinit var tvTitleHeader: TextView

    private var currentMode = BackupService.MODE_CAMERA
    private var backupEmAndamento = false

    private lateinit var manageStorageLauncher: ActivityResultLauncher<Intent>

    companion object {
        const val ACTION_UPDATE = "com.backup.android.UPDATE"
        const val EXTRA_TIPO = "tipo"
        const val EXTRA_MENSAGEM = "mensagem"
        const val EXTRA_PROGRESSO = "progresso"
        const val EXTRA_TOTAL = "total"
        const val EXTRA_ATUAL = "atual"
        const val EXTRA_COPIADOS = "copiados"
        const val EXTRA_IGNORADOS = "ignorados"
        const val EXTRA_ERROS = "erros"
        const val EXTRA_ETA = "eta"
        const val TIPO_LOG = "log"
        const val TIPO_PROGRESSO = "progresso"
        const val TIPO_CONCLUIDO = "concluido"
        const val TIPO_ERRO = "erro"
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getStringExtra(EXTRA_TIPO)) {
                TIPO_PROGRESSO -> {
                    val progresso = intent.getIntExtra(EXTRA_PROGRESSO, 0)
                    val total = intent.getIntExtra(EXTRA_TOTAL, 0)
                    val atual = intent.getStringExtra(EXTRA_ATUAL) ?: ""
                    val copiados = intent.getIntExtra(EXTRA_COPIADOS, 0)
                    val ignorados = intent.getIntExtra(EXTRA_IGNORADOS, 0)
                    val erros = intent.getIntExtra(EXTRA_ERROS, 0)
                    val eta = intent.getStringExtra(EXTRA_ETA) ?: ""
                    atualizarProgresso(progresso, total, atual, copiados, ignorados, erros, eta)
                }
                TIPO_LOG -> {
                    val msg = intent.getStringExtra(EXTRA_MENSAGEM) ?: ""
                    adicionarLog(msg)
                }
                TIPO_CONCLUIDO -> {
                    val copiados = intent.getIntExtra(EXTRA_COPIADOS, 0)
                    val ignorados = intent.getIntExtra(EXTRA_IGNORADOS, 0)
                    val erros = intent.getIntExtra(EXTRA_ERROS, 0)
                    onBackupConcluido(copiados, ignorados, erros)
                }
                TIPO_ERRO -> {
                    val msg = intent.getStringExtra(EXTRA_MENSAGEM) ?: ""
                    onErroGrave(msg)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Binding UI
        btnBackup      = findViewById(R.id.btnBackup)
        btnLimpar      = findViewById(R.id.btnLimpar)
        tvStatus       = findViewById(R.id.tvStatus)
        tvPercent      = findViewById(R.id.tvPercent)
        tvArquivoAtual = findViewById(R.id.tvArquivoAtual)
        tvContador     = findViewById(R.id.tvContador)
        tvEta          = findViewById(R.id.tvEta)
        tvLog          = findViewById(R.id.tvLog)
        
        val cvCopiados = findViewById<CardView>(R.id.statCopiados)
        tvCopiados = cvCopiados.findViewById(R.id.tvValue)
        cvCopiados.findViewById<TextView>(R.id.tvLabel).text = "Copiados"
        tvCopiados.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_light))

        val cvIgnorados = findViewById<CardView>(R.id.statIgnorados)
        tvIgnorados = cvIgnorados.findViewById(R.id.tvValue)
        cvIgnorados.findViewById<TextView>(R.id.tvLabel).text = "Já existem"
        tvIgnorados.setTextColor(ContextCompat.getColor(this, android.R.color.holo_orange_light))

        val cvErros = findViewById<CardView>(R.id.statErros)
        tvErros = cvErros.findViewById(R.id.tvValue)
        cvErros.findViewById<TextView>(R.id.tvLabel).text = "Erros"
        tvErros.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_light))

        progressBar    = findViewById(R.id.progressBar)
        scrollLog      = findViewById(R.id.scrollLog)
        bottomNavigation = findViewById(R.id.bottomNavigation)
        
        tvIconHeader   = findViewById(R.id.tvIconHeader)
        tvTitleHeader  = findViewById(R.id.tvTitleHeader)

        // Listeners
        btnBackup.setOnClickListener { iniciarBackup() }
        btnLimpar.setOnClickListener { confirmarLimpeza() }
        
        bottomNavigation.setOnItemSelectedListener { item ->
            if (backupEmAndamento) {
                adicionarLog("⚠️ Backup em andamento. Aguarde terminar.")
                return@setOnItemSelectedListener false
            }
            when (item.itemId) {
                R.id.nav_camera -> switchMode(BackupService.MODE_CAMERA)
                R.id.nav_others -> switchMode(BackupService.MODE_OTHERS)
            }
            true
        }

        manageStorageLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
                adicionarLog("✅ Permissão de acesso total aos arquivos concedida.")
                confirmarLimpeza()
            } else {
                adicionarLog("❌ Permissão MANAGE_EXTERNAL_STORAGE não concedida.")
            }
        }

        val filter = IntentFilter(ACTION_UPDATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.RECEIVER_NOT_EXPORTED
            } else {
                0
            }
            ContextCompat.registerReceiver(this, receiver, filter, flags)
        } else {
            registerReceiver(receiver, filter)
        }
    }

    private fun switchMode(mode: String) {
        currentMode = mode
        if (mode == BackupService.MODE_CAMERA) {
            tvIconHeader.text = "📷"
            tvTitleHeader.text = "Backup Câmera"
            btnBackup.text = "▶  INICIAR BACKUP CÂMERA"
        } else {
            tvIconHeader.text = "📁"
            tvTitleHeader.text = "Backup Outros"
            btnBackup.text = "▶  INICIAR BACKUP OUTROS"
        }
        resetarContadores()
        tvStatus.text = "Modo: ${tvTitleHeader.text}"
        adicionarLog("🔄 Modo alterado para: ${tvTitleHeader.text}")
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(receiver)
    }

    private fun iniciarBackup() {
        if (backupEmAndamento) return
        if (!verificarPermissoesEspecial()) return

        backupEmAndamento = true
        btnBackup.isEnabled = false
        btnBackup.text = "⏳ PROCESSANDO..."
        resetarContadores()

        val intent = Intent(this, BackupService::class.java).apply {
            putExtra(BackupService.EXTRA_MODE, currentMode)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun confirmarLimpeza() {
        if (!verificarPermissoesEspecial()) return

        val msg = if (currentMode == BackupService.MODE_CAMERA) 
            "Isso vai apagar a pasta DCIM (Fotos da Câmera)." 
        else 
            "Isso vai apagar as pastas de WhatsApp, Telegram, Downloads, etc."

        AlertDialog.Builder(this)
            .setTitle("⚠️ Limpar arquivos?")
            .setMessage("$msg\n\nTem certeza que o backup foi concluído?")
            .setPositiveButton("SIM, APAGAR") { _, _ -> executarLimpeza() }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun executarLimpeza() {
        adicionarLog("🗑 Iniciando limpeza ($currentMode)...")
        tvStatus.text = "Limpando $currentMode..."
        backupEmAndamento = true
        btnBackup.isEnabled = false

        btnBackup.text = "⏳  BACKUP EM ANDAMENTO..."
        btnLimpar.isEnabled = false
        tvLog.text = ""
        tvStatus.text = "Conectando ao PC..."
        btnBackup.text = "⏳ LIMPANDO..."
        resetarContadores()

        val intent = Intent(this, BackupService::class.java).apply {
            action = BackupService.ACTION_LIMPAR
            putExtra(BackupService.EXTRA_MODE, currentMode)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun atualizarProgresso(p: Int, t: Int, a: String, c: Int, i: Int, e: Int, eta: String) {
        progressBar.progress = p
        tvPercent.text = "$p%"
        tvArquivoAtual.text = a
        tvContador.text = "$c/$t"
        
        // Se estiver limpando, vamos mostrar como "Apagados" e "Falhas"
        if (tvStatus.text.toString().contains("Limpando")) {
            tvCopiados.text = c.toString() // Aqui representará apagados
            tvErros.text = e.toString() // Aqui representará falhas
        } else {
            tvCopiados.text = c.toString()
            tvIgnorados.text = i.toString()
            tvErros.text = e.toString()
        }

        if (eta.isNotEmpty()) tvEta.text = "⏱ ETA: $eta"
    }

    private fun adicionarLog(m: String) {
        val h = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        tvLog.append("[$h] $m\n")
        scrollLog.post { scrollLog.fullScroll(View.FOCUS_DOWN) }
    }

    private fun onBackupConcluido(c: Int, i: Int, e: Int) {
        backupEmAndamento = false
        btnBackup.isEnabled = true
        btnBackup.text = "🔄  FAZER BACKUP NOVAMENTE"
        btnLimpar.isEnabled = true
        tvStatus.text = "✅ Backup concluído!"
        tvPercent.text = "100%"
        progressBar.progress = 100
        tvEta.text = ""

        if (erros == 0) {
            adicionarLog("✅ Backup concluído! $copiados copiados, $ignorados já existiam.")
        } else {
            adicionarLog("⚠️ Backup com $erros erros. Rode novamente antes de limpar.")
        }
        btnBackup.text = if (currentMode == BackupService.MODE_CAMERA) "▶  INICIAR BACKUP CÂMERA" else "▶  INICIAR BACKUP OUTROS"
        tvStatus.text = "✅ Concluído!"
        adicionarLog("✅ Finalizado: $c copiados, $i já existiam, $e erros.")
    }

    private fun onErroGrave(m: String) {
        backupEmAndamento = false
        btnBackup.isEnabled = true
        btnBackup.text = "▶  TENTAR NOVAMENTE"
        btnLimpar.isEnabled = true
        tvStatus.text = "❌ Erro: $mensagem"
        adicionarLog("❌ ERRO: $mensagem")
    }

    private fun confirmarLimpeza() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            AlertDialog.Builder(this)
                .setTitle("Permissão necessária")
                .setMessage("Para apagar arquivos do DCIM é necessário acesso total aos arquivos.\n\nVocê será redirecionado para Configurações.")
                .setPositiveButton("ABRIR CONFIGURAÇÕES") { _, _ ->
                    try {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION
                        ).setData(Uri.parse("package:$packageName"))
                        manageStorageLauncher.launch(intent)
                    } catch (e: Exception) {
                        adicionarLog("❌ Não foi possível abrir Configurações: ${e.message}")
                    }
                }
                .setNegativeButton("Cancelar", null)
                .show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("⚠️ Limpar DCIM do celular?")
            .setMessage("Isso vai apagar PERMANENTEMENTE todos os arquivos da pasta DCIM do celular.\n\nTem certeza? Esta ação não pode ser desfeita.")
            .setPositiveButton("SIM, APAGAR") { _, _ -> executarLimpeza() }
            .setNegativeButton("Cancelar", null)
            .show()

        btnBackup.text = if (currentMode == BackupService.MODE_CAMERA) "▶  INICIAR BACKUP CÂMERA" else "▶  INICIAR BACKUP OUTROS"
        tvStatus.text = "❌ Erro: $m"
        adicionarLog("❌ ERRO: $m")

    }

    private fun verificarPermissoesEspecial(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                AlertDialog.Builder(this)
                    .setTitle("Permissão Especial")
                    .setMessage("O App precisa de acesso total para fazer backup e limpeza de todas as pastas.")
                    .setPositiveButton("Configurar") { _, _ ->
                        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                        intent.data = Uri.parse("package:$packageName")
                        startActivity(intent)
                    }
                    .show()
                return false
            }
        }
        return true
    }

    private fun resetarContadores() {
        tvCopiados.text = "0"
        tvIgnorados.text = "0"
        tvErros.text = "0"
        tvPercent.text = "0%"
        progressBar.progress = 0
        tvArquivoAtual.text = "—"
        tvEta.text = ""
    }
}
