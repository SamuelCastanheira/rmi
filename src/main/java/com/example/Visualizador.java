package com.example;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;

public class Visualizador {

    private final ObjectMapper mapper = new ObjectMapper();

    public void iniciar() throws IOException {
        InetAddress grupo = InetAddress.getByName(Main.GRUPO_MULTICAST);

        try (MulticastSocket socket = new MulticastSocket(Main.PORTA_MULTICAST)) {
            socket.joinGroup(grupo);

            System.out.println("Visualizador iniciado!");
            System.out.println("Grupo: " + Main.GRUPO_MULTICAST);
            System.out.println("Porta: " + Main.PORTA_MULTICAST);
            System.out.println("Aguardando jogadas...\n");

            while (true) {
                byte[] buffer = new byte[4096];

                DatagramPacket pacote =
                        new DatagramPacket(buffer, buffer.length);

                socket.receive(pacote);

                String mensagem = new String(
                        pacote.getData(),
                        pacote.getOffset(),
                        pacote.getLength(),
                        java.nio.charset.StandardCharsets.UTF_8
                );

                exibirJogada(mensagem);
            }
        }
    }

    private void exibirJogada(String mensagem) {
        try {
            JsonNode json = mapper.readTree(mensagem);
            JsonNode tabuleiro = json.get("Tabuleiro");

            if (tabuleiro == null || !tabuleiro.isArray()
                    || tabuleiro.size() != 9) {
                System.err.println("Mensagem recebida com tabuleiro inválido.");
                return;
            }

            // Limpa o terminal e posiciona o cursor no início.
            System.out.print("\033[H\033[2J");
            System.out.flush();

            System.out.println("======= JOGO DA VELHA =======");
            System.out.println("Jogada número: " + json.path("Numero").asInt());
            System.out.println();

            for (int i = 0; i < 9; i++) {
                System.out.print(" " + simbolo(tabuleiro.get(i).asInt()) + " ");

                if (i % 3 != 2) {
                    System.out.print("|");
                } else if (i != 8) {
                    System.out.println("\n---+---+---");
                }
            }

            System.out.println("\n\nResultado: " + json.path("Resultado").asText());
            System.out.println("=============================");
        } catch (Exception e) {
            System.err.println("Erro ao interpretar jogada: " + e.getMessage());
            System.err.println("Mensagem recebida: " + mensagem);
        }
    }

    private String simbolo(int valor) {
        return switch (valor) {
            case 1 -> "X";
            case 2 -> "O";
            default -> " ";
        };
    }
}
