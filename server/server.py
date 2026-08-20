import socket
import struct
import os

# Configurações
HOST = '0.0.0.0'  # Escuta em todas as interfaces
PORT = 9999
BACKUP_DIR = r'C:\Users\giselenet\Backup_Camera_Android'

def recv_all(sock, n):
    """Auxiliar para garantir que n bytes sejam lidos do socket."""
    data = bytearray()
    while len(data) < n:
        packet = sock.recv(n - len(data))
        if not packet:
            return None
        data.extend(packet)
    return data

def start_server():
    if not os.path.exists(BACKUP_DIR):
        os.makedirs(BACKUP_DIR)
        print(f"[*] Pasta de backup criada: {BACKUP_DIR}")

    server_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server_socket.bind((HOST, PORT))
    server_socket.listen(1)
    print(f"[*] Servidor aguardando conexão na porta {PORT}...")

    while True:
        try:
            client_socket, addr = server_socket.accept()
            print(f"[*] Conexão aceita de {addr}")
            handle_client(client_socket)
        except KeyboardInterrupt:
            break
        except Exception as e:
            print(f"[!] Erro no loop principal: {e}")

def handle_client(sock):
    try:
        # 1. Recebe total de arquivos (4 bytes - Int)
        data = recv_all(sock, 4)
        if not data:
            print("[!] Conexão fechada prematuramente ao ler total de arquivos.")
            return
        total_files = struct.unpack('>i', data)[0]
        print(f"[*] Total de arquivos a processar: {total_files}")

        if total_files == -1:
            print("[*] Comando de encerramento recebido.")
            return

        for i in range(total_files):
            # 2. Recebe tamanho do caminho (4 bytes)
            data = recv_all(sock, 4)
            if not data:
                print(f"[!] Falha ao ler tamanho do caminho do arquivo {i+1}")
                break
            path_len = struct.unpack('>i', data)[0]

            # 3. Recebe o caminho relativo
            path_bytes = recv_all(sock, path_len)
            if not path_bytes:
                print(f"[!] Falha ao ler caminho do arquivo {i+1}")
                break
            relative_path = path_bytes.decode('utf-8')

            # 4. Recebe o tamanho do arquivo (8 bytes - Long)
            data = recv_all(sock, 8)
            if not data:
                print(f"[!] Falha ao ler tamanho do conteúdo do arquivo {i+1}")
                break
            file_size = struct.unpack('>q', data)[0]

            full_path = os.path.join(BACKUP_DIR, relative_path)
            os.makedirs(os.path.dirname(full_path), exist_ok=True)

            # 5. Verifica se arquivo já existe (e tem o mesmo tamanho)
            if os.path.exists(full_path) and os.path.getsize(full_path) == file_size:
                print(f"[-] [{i+1}/{total_files}] Já existe: {relative_path}")
                sock.sendall(struct.pack('b', 1)) # Resposta 1 = Já existe
                continue

            print(f"[+] [{i+1}/{total_files}] Recebendo: {relative_path} ({file_size} bytes)")
            sock.sendall(struct.pack('b', 0)) # Resposta 0 = Pode enviar

            # 6. Recebe o conteúdo do arquivo
            with open(full_path, 'wb') as f:
                bytes_received = 0
                while bytes_received < file_size:
                    remaining = file_size - bytes_received
                    chunk_size = min(remaining, 64 * 1024)
                    chunk = sock.recv(chunk_size)
                    if not chunk:
                        print(f"[!] Conexão perdida durante recebimento de {relative_path}")
                        raise ConnectionError("Conexão perdida")
                    f.write(chunk)
                    bytes_received += len(chunk)

            # 7. Envia confirmação (1 byte)
            sock.sendall(struct.pack('b', 1))

        print("[*] Backup finalizado ou interrompido.")

    except Exception as e:
        print(f"[!] Erro durante o backup: {e}")
    finally:
        sock.close()

if __name__ == "__main__":
    start_server()
