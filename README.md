# Backup Câmera Android - App

## Descrição

App Android que faz backup das fotos e vídeos da pasta DCIM do celular para um servidor rodando no PC, via cabo USB e túnel ADB. Após o backup concluído sem erros, oferece opção de apagar os arquivos do celular para liberar espaço.

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

## Requisitos

- **Celular**: Android 8.0 ou superior (API 26+)
- **Cabo USB** conectado ao PC
- **Depuração USB** ativada nas opções de desenvolvedor do celular
- **ADB** instalado no PC (vem com Android Studio / Platform Tools)
- **Servidor receptor** rodando no PC, escutando na porta `9999` (código fora deste repositório)
- Pasta de destino configurada no servidor: `C:\Users\giselenet\Backup_Camera_Android\`

## Como importar no Android Studio

1. Abra o Android Studio
2. File > New > Import Project
3. Selecione a pasta `BackupApp`
4. Aguarde o Gradle sincronizar
5. Conecte o celular com Depuração USB ativada
6. Clique em **Run ▶**

## Como usar

A comunicação acontece via **socket TCP tunelado pelo ADB**:

```
[Celular] --USB--> [ADB tunnel] --TCP:9999--> [Servidor no PC]
```


### Passo a passo

1. Conecte o celular via USB ao PC
2. No PC, abra um terminal (PowerShell ou CMD) e execute:

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
4. 
   Abra **outro** terminal no PC e execute:
   ```powershell
   adb reverse tcp:9999 tcp:9999
   ```

3. Inicie o servidor receptor no PC (ele ficará escutando na porta `9999`)
4. Abra o app **Backup Câmera** no celular
5. Toque em **▶ INICIAR BACKUP**
6. Aguarde a conclusão — o progresso aparece em tempo real

## Funcionalidades do app

### Tela principal

- **Cabeçalho**: ícone, título e status da conexão
- **Card de progresso**: barra de progresso, percentual, nome do arquivo atual, contador (`copiados/total`) e ETA estimado (`⏱ ETA: 00h 05m 12s`)
- **Estatísticas**: três cartões com totais de
  - **Copiados** (verde) — arquivos enviados com sucesso
  - **Já existem** (amarelo) — arquivos ignorados porque já estavam no PC
  - **Erros** (vermelho) — arquivos que falharam
- **Log**: histórico detalhado com timestamp, ex.: `[14:32:11] OK  DCIM/Camera/IMG_0001.jpg`
- **Botão INICIAR BACKUP**: dispara o serviço em foreground
- **Botão LIMPAR DCIM DO CELULAR**: aparece apenas após backup concluído sem erros

### Limpeza do DCIM

Após um backup concluído com **zero erros**, o botão **🗑 LIMPAR DCIM DO CELULAR** é exibido. Ao tocar, uma confirmação é solicitada antes de apagar permanentemente os arquivos do celular.

### Arquivos ignorados

Arquivos cujo nome começa com `.trashed` (geralmente itens na lixeira do Android) são **ignorados automaticamente** durante a transferência, evitando erros.

## Permissões

| Permissão | SDK mínimo | Motivo |
|-----------|-----------|--------|
| `READ_MEDIA_IMAGES` | Android 13 (Tiramisu) | Ler fotos do celular |
| `READ_MEDIA_VIDEO` | Android 13 (Tiramisu) | Ler vídeos do celular |
| `READ_EXTERNAL_STORAGE` | Android 12 ou inferior | Ler arquivos do armazenamento |
| `INTERNET` | Todos | Comunicação via socket TCP |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC` | Todos | Executar o backup em background |
| `POST_NOTIFICATIONS` | Android 13+ | Notificação de progresso do backup |

Na primeira execução, o app solicita automaticamente as permissões de leitura de mídia.

## Pasta de destino

O servidor rodando no PC é responsável por salvar os arquivos. A pasta configurada atualmente é:

```
C:\Users\giselenet\Backup_Camera_Android\
```

> O código do servidor **não faz parte deste repositório**. Apenas o cliente Android está incluído. O servidor deve estar em execução antes de iniciar o backup pelo app.

4. **No Celular**: Abra o app e toque em **INICIAR BACKUP**.

## Estrutura do Servidor

O servidor foi criado em Python para ser simples e leve:
- **Local:** `server/server.py`
- **Porta:** 9999
- **Pasta de destino:** `C:\Users\giselenet\Backup_Camera_Android\`

##Fim
