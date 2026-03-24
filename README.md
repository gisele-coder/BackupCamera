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

### Antes de usar:

1. Conecte o celular via USB
2. No PC, execute no PowerShell/terminal:
   ```
   adb reverse tcp:9999 tcp:9999
   ```
3. Execute o servidor no PC (próximo passo: criar servidor Windows)
4. Abra o app no celular e toque em INICIAR BACKUP

## Próximo passo

Criar o servidor Windows (C# ou Python) que:
- Fica escutando na porta 9999
- Recebe os arquivos do celular
- Salva em C:\Users\giselenet\Backup_Camera_Android\
- Mostra progresso em tempo real na tela do PC
