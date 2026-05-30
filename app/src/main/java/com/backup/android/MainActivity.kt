package com.backup.android

import android.Manifest
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.ProgressBar
import android.provider.Settings
import android.net.Uri
import android.os.Environment
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
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

    private var backupEmAndamento = false
    private var backupConcluido = false
    private var totalErros = 0

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
                    totalErros = erros
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
                    totalErros = erros
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

        btnBackup     = findViewById(R.id.btnBackup)
        btnLimpar     = findViewById(R.id.btnLimpar)
        tvStatus      = findViewById(R.id.tvStatus)
        tvPercent     = findViewById(R.id.tvPercent)
        tvArquivoAtual = findViewById(R.id.tvArquivoAtual)
        tvContador    = findViewById(R.id.tvContador)
        tvEta         = findViewById(R.id.tvEta)
        tvLog         = findViewById(R.id.tvLog)
        tvCopiados    = findViewById(R.id.tvCopiados)
        tvIgnorados   = findViewById(R.id.tvIgnorados)
        tvErros       = findViewById(R.id.tvErros)
        progressBar   = findViewById(R.id.progressBar)
        scrollLog     = findViewById(R.id.scrollLog)

        btnBackup.setOnClickListener { iniciarBackup() }
        btnLimpar.setOnClickListener { confirmarLimpeza() }

        val filter = IntentFilter(ACTION_UPDATE)
        // Android U+ exige flag explícita de exported state para receivers.
        ContextCompat.registerReceiver(
            this,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(receiver)
    }

    private fun iniciarBackup() {
        if (backupEmAndamento) return

        if (!verificarPermissoesEspecial()) return

        // Verifica permissões
        val permissoes = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED)
                permissoes.add(Manifest.permission.READ_MEDIA_IMAGES)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED)
                permissoes.add(Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
                permissoes.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        if (permissoes.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissoes.toTypedArray(), 1001)
            return
        }

        backupEmAndamento = true
        backupConcluido   = false
        btnBackup.isEnabled = false
        btnBackup.text = "⏳  BACKUP EM ANDAMENTO..."
        tvLog.text = ""
        tvStatus.text = "Conectando ao PC..."
        resetarContadores()

        val intent = Intent(this, BackupService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun atualizarProgresso(
        progresso: Int, total: Int, arquivo: String,
        copiados: Int, ignorados: Int, erros: Int, eta: String
    ) {
        progressBar.progress = progresso
        tvPercent.text = "$progresso%"
        tvArquivoAtual.text = arquivo
        tvContador.text = "$copiados/${total}"
        tvCopiados.text = copiados.toString()
        tvIgnorados.text = ignorados.toString()
        tvErros.text = erros.toString()
        if (eta.isNotEmpty()) tvEta.text = "⏱ ETA: $eta"
        tvStatus.text = "Backup em andamento..."
    }

    private fun adicionarLog(mensagem: String) {
        val hora = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val linhaAtual = tvLog.text.toString()
        tvLog.text = "$linhaAtual[$hora] $mensagem\n"
        scrollLog.post { scrollLog.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun onBackupConcluido(copiados: Int, ignorados: Int, erros: Int) {
        backupEmAndamento = false
        backupConcluido = true
        btnBackup.isEnabled = true
        btnBackup.text = "🔄  FAZER BACKUP NOVAMENTE"
        tvStatus.text = "✅ Backup concluído!"
        tvPercent.text = "100%"
        progressBar.progress = 100
        tvEta.text = ""

        if (erros == 0) {
            adicionarLog("✅ Backup concluído! $copiados copiados, $ignorados já existiam.")
        } else {
            adicionarLog("⚠️ Backup com $erros erros. Rode novamente antes de limpar.")
        }
    }

    private fun onErroGrave(mensagem: String) {
        backupEmAndamento = false
        btnBackup.isEnabled = true
        btnBackup.text = "▶  TENTAR NOVAMENTE"
        tvStatus.text = "❌ Erro: $mensagem"
        adicionarLog("❌ ERRO: $mensagem")
    }

    private fun confirmarLimpeza() {
        if (!verificarPermissoesEspecial()) return

        AlertDialog.Builder(this)
            .setTitle("⚠️ Limpar DCIM do celular?")
            .setMessage("Isso vai apagar PERMANENTEMENTE todos os arquivos da pasta DCIM do celular.\n\nTem certeza que o backup foi feito corretamente?")
            .setPositiveButton("SIM, APAGAR") { _, _ -> executarLimpeza() }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun executarLimpeza() {
        adicionarLog("🗑 Iniciando limpeza da DCIM...")
        tvStatus.text = "Limpando DCIM..."

        val intent = Intent(this, BackupService::class.java).apply {
            action = BackupService.ACTION_LIMPAR
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun verificarPermissoesEspecial(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                AlertDialog.Builder(this)
                    .setTitle("Permissão Necessária")
                    .setMessage("Para apagar arquivos da pasta DCIM, o App precisa da permissão 'Acesso a todos os arquivos'.\n\nDeseja configurar agora?")
                    .setPositiveButton("Configurar") { _, _ ->
                        try {
                            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                            intent.addCategory("android.intent.category.DEFAULT")
                            intent.data = Uri.parse("package:${packageName}")
                            startActivity(intent)
                        } catch (e: Exception) {
                            val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                            startActivity(intent)
                        }
                    }
                    .setNegativeButton("Agora não", null)
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
        tvContador.text = "0/0"
        tvPercent.text = "0%"
        progressBar.progress = 0
        tvArquivoAtual.text = "—"
        tvEta.text = ""
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001 && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            iniciarBackup()
        } else {
            tvStatus.text = "❌ Permissão negada. Necessário para ler os arquivos."
        }
    }
}
