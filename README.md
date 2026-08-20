# Backup Câmera Android - App

## Estrutura do projeto

```
BackupApp/
  app/src/main/
    AndroidManifest.xml
    java/com/backup/android/
      MainActivity.kt       <- Tela principal
      BackupService.kt      <- Serviço de backup em background
    res/
      layout/activity_main.xml
      drawable/progress_bar.xml
      values/strings.xml
      values/themes.xml
  app/build.gradle
```

## Como importar no Android Studio

1. Abra o Android Studio
2. File > New > Import Project
3. Selecione a pasta BackupApp
4. Aguarde o Gradle sincronizar
5. Conecte o celular com Depuração USB ativada
6. Clique em Run ▶

## Como funciona

O app usa comunicação via **socket TCP tunelado pelo ADB**:

```
[Celular] --USB--> [ADB tunnel] --TCP:9999--> [Servidor no PC]
```

### Antes de usar (IMPORTANTE):

1. **Conecte o celular** via USB com Depuração USB ativada.
2. **Inicie o servidor no PC**:
   No terminal do PC, navegue até a pasta do projeto e execute:
   ```powershell
   cd server
   python server.py
   ```
   *(O servidor deve mostrar: "Servidor aguardando conexão na porta 9999...")*
3. **Configure o túnel ADB**:
   Abra **outro** terminal no PC e execute:
   ```powershell
   adb reverse tcp:9999 tcp:9999
   ```
4. **No Celular**: Abra o app e toque em **INICIAR BACKUP**.

## Estrutura do Servidor

O servidor foi criado em Python para ser simples e leve:
- **Local:** `server/server.py`
- **Porta:** 9999
- **Pasta de destino:** `C:\Users\giselenet\Backup_Camera_Android\`

##Fim
