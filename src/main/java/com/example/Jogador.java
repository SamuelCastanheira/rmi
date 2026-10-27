package com.example;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.Scanner;


public class Jogador {

    private static final int VAZIO = 0;
    private static final int X = 1;
    private static final int O = 2;

    private static final int[][] LINHAS = {
            {0, 1, 2}, {3, 4, 5}, {6, 7, 8},
            {0, 3, 6}, {1, 4, 7}, {2, 5, 8},
            {0, 4, 8}, {2, 4, 6}
    };

    private final ObjectMapper mapper = new ObjectMapper();
    private final Scanner scanner = new Scanner(System.in);
    private final int[] tabuleiro = new int[9];

    private ServerSocket serverSocket;
    private Socket socket;
    private BufferedReader entrada;
    private PrintWriter saida;
    private DatagramSocket multicastSocket;
    private InetAddress grupoMulticast;

    private int meuSimbolo;
    private int numeroJogada = 0;
    private boolean minhaVez;
    private boolean fim;
    

     public void iniciar(String ipServidor) throws Exception {
        try {
            // Prepara a porta TCP antes de solicitar o pareamento.
            serverSocket = new ServerSocket(0);

            int minhaPorta = serverSocket.getLocalPort();

            String meuIp = InetAddress.getLocalHost().getHostAddress();

            System.out.println("Seu IP: " + meuIp);
            System.out.println("Sua porta TCP: " + minhaPorta);
            System.out.println("Procurando adversário...");

            // Fase 1: pareamento via RMI.
            Registry registry = LocateRegistry.getRegistry(
                    ipServidor,
                    Main.PORTA
            );

            IServidor servidor =
                    (IServidor) registry.lookup(Main.NOME_SERVICO);

            ObjectNode pedido = mapper.createObjectNode();
            pedido.put("ip", meuIp);
            pedido.put("porta", minhaPorta);
            pedido.put("protocolo", "tcp");

            String resposta = servidor.getOponent(
                    mapper.writeValueAsString(pedido)
            );

            JsonNode match = mapper.readTree(resposta);

            if (match.has("erro")) {
                System.out.println(
                        "Não foi possível encontrar adversário: "
                                + match.get("erro").asText()
                );
                return;
            }

            String ipAdversario = match.get("ip").asText();
            int portaAdversario = match.get("porta").asInt();
            boolean inicia = match.get("inicia").asBoolean();

            meuSimbolo = inicia ? X : O;
            minhaVez = inicia;

            System.out.println("Adversário encontrado: " + ipAdversario);
            System.out.println("Você joga com " + simbolo(meuSimbolo));

            // Fase 2: conexão TCP direta entre os jogadores.
            if (inicia) {
                socket = new Socket(ipAdversario, portaAdversario);
                serverSocket.close();
            } else {
                System.out.println("Aguardando conexão do adversário...");
                socket = serverSocket.accept();
                serverSocket.close();
            }

            entrada = new BufferedReader(
                    new InputStreamReader(
                            socket.getInputStream(),
                            StandardCharsets.UTF_8
                    )
            );

            saida = new PrintWriter(
                    socket.getOutputStream(),
                    true,
                    StandardCharsets.UTF_8
            );

            multicastSocket = new DatagramSocket();
            grupoMulticast = InetAddress.getByName(
                    Main.GRUPO_MULTICAST
            );

            System.out.println("Partida iniciada!");
            imprimirTabuleiro();

            jogarPartida();

        } catch (Exception e) {
            System.err.println("Erro: " + e.getMessage());
        } finally {
            fecharSockets();
        }
    }

    private void jogarPartida() throws IOException {
        while (!fim) {
            if (minhaVez) {
                realizarJogada();
            } else {
                receberJogada();
            }
        }

        System.out.println("Partida encerrada.");
    }

    private void realizarJogada() throws IOException {
        int posicao;

        while (true) {
            System.out.print(
                    "Sua jogada (1-9): "
            );

            String texto = scanner.nextLine().trim();

            try {
                posicao = Integer.parseInt(texto) - 1;
            } catch (NumberFormatException e) {
                System.out.println("Digite um número de 1 a 9.");
                continue;
            }

            if (posicao < 0 || posicao >= 9) {
                System.out.println("Posição inválida.");
                continue;
            }

            if (tabuleiro[posicao] != VAZIO) {
                System.out.println("Essa casa já está ocupada.");
                continue;
            }

            break;
        }

        tabuleiro[posicao] = meuSimbolo;

        String resultado = calcularResultado();

        ObjectNode mensagem = criarMensagem(
                numeroJogada,
                posicao,
                resultado
        );

        enviarTcp(mensagem);
        enviarMulticast(mensagem);

        numeroJogada++;
        minhaVez = false;

        imprimirTabuleiro();

        if (resultado.equals("vit")) {
            System.out.println("Você venceu!");
            fim = true;
        } else if (resultado.equals("emp")) {
            System.out.println("Empate!");
            fim = true;
        } else {
            System.out.println("Aguardando o adversário...");
        }
    }

    private void receberJogada() throws IOException {
        String linha = entrada.readLine();

        if (linha == null) {
            throw new IOException("O adversário desconectou.");
        }

        JsonNode mensagem = mapper.readTree(linha);

        String resultado = mensagem.get("Resultado").asText();

        if (resultado.equals("der")) {
            System.out.println("O adversário confirmou a derrota.");
            fim = true;
            return;
        }

        if (resultado.equals("vit") || resultado.equals("emp")) {
            throw new IOException(
                    "Mensagem final inesperada do adversário."
            );
        }

        if (!resultado.equals("jog")) {
            throw new IOException("Resultado de jogada inválido.");
        }

        int numero = mensagem.get("Numero").asInt();
        int posicao = mensagem.get("Jogada").asInt();

        if (numero != numeroJogada) {
            throw new IOException("Número de jogada inválido.");
        }

        if (posicao < 0 || posicao >= 9
                || tabuleiro[posicao] != VAZIO) {
            throw new IOException("Jogada inválida recebida.");
        }

        int simboloAdversario = (meuSimbolo == X) ? O : X;
        tabuleiro[posicao] = simboloAdversario;

        String resultadoCalculado = calcularResultado();

        if (!resultadoCalculado.equals("jog")) {
            throw new IOException(
                    "O adversário enviou um resultado inconsistente."
            );
        }

        numeroJogada++;

        enviarMulticast(mensagem);

        imprimirTabuleiro();

        System.out.println(
                "O adversário jogou na posição " + (posicao + 1) + "."
        );

        minhaVez = true;
    }

    private ObjectNode criarMensagem(
            int numero,
            int posicao,
            String resultado
    ) {
        ObjectNode mensagem = mapper.createObjectNode();

        mensagem.put("Numero", numero);
        mensagem.put("Jogada", posicao);

        ArrayNode casas = mensagem.putArray("Tabuleiro");

        for (int casa : tabuleiro) {
            casas.add(casa);
        }

        mensagem.put("Resultado", resultado);

        return mensagem;
    }

    private String calcularResultado() {
        for (int[] linha : LINHAS) {
            int a = tabuleiro[linha[0]];

            if (a != VAZIO
                    && a == tabuleiro[linha[1]]
                    && a == tabuleiro[linha[2]]) {
                return a == meuSimbolo ? "vit" : "der";
            }
        }

        for (int casa : tabuleiro) {
            if (casa == VAZIO) {
                return "jog";
            }
        }

        return "emp";
    }

    private void enviarTcp(JsonNode mensagem) throws IOException {
        saida.println(mapper.writeValueAsString(mensagem));

        if (saida.checkError()) {
            throw new IOException("Falha ao enviar jogada.");
        }
    }

    private void enviarMulticast(JsonNode mensagem) throws IOException {
        byte[] dados = mapper.writeValueAsBytes(mensagem);

        DatagramPacket pacote = new DatagramPacket(
                dados,
                dados.length,
                grupoMulticast,
                Main.PORTA_MULTICAST
        );

        multicastSocket.send(pacote);
    }

    private void imprimirTabuleiro() {
        System.out.println();
        System.out.println(" " + casa(0) + " | " + casa(1) + " | " + casa(2));
        System.out.println("---+---+---");
        System.out.println(" " + casa(3) + " | " + casa(4) + " | " + casa(5));
        System.out.println("---+---+---");
        System.out.println(" " + casa(6) + " | " + casa(7) + " | " + casa(8));
        System.out.println();
    }

    private String casa(int posicao) {
        if (tabuleiro[posicao] == VAZIO) {
            return String.valueOf(posicao + 1);
        }

        return simbolo(tabuleiro[posicao]);
    }

    private String simbolo(int valor) {
        return switch (valor) {
            case X -> "X";
            case O -> "O";
            default -> " ";
        };
    }

    private void fecharSockets() {
        try {
            if (entrada != null) entrada.close();
        } catch (IOException ignored) {}

        if (saida != null) saida.close();

        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {}

        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {}

        if (multicastSocket != null) multicastSocket.close();
    }
}