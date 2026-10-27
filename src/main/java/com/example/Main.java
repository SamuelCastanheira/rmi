package com.example;

public class Main {

public static final int PORTA = 9816;
public static final String NOME_SERVICO = "JogoDaVelha";

public static final String GRUPO_MULTICAST = "225.1.1.16";
public static final int PORTA_MULTICAST = 8816;

public static void main(String[] args) throws Exception {
    if (args.length == 0) {
        System.out.println("Uso:");
        System.out.println("  servidor <ipServidor>");
        System.out.println("  jogador <ipServidor>");
        return;
    }

    String modo = args[0];
    String ipServidor = args.length >= 2 ? args[1] : "localhost";

    switch (modo) {
        case "servidor" -> {
            System.setProperty(
                    "java.rmi.server.hostname",
                    ipServidor
            );

            Servidor.iniciar();
             System.out.println("Servidor aguardando jogadores...");

        // Mantém o processo ativo.
        new java.util.concurrent.CountDownLatch(1).await();
        }

        case "jogador" -> {
            Jogador jogador = new Jogador();
            jogador.iniciar(ipServidor);
        }

        case "visualizador" -> {
            new Visualizador().iniciar();
        }

        default -> System.out.println(
                "Argumento inválido. Use 'servidor' ou 'jogador'."
        );
    }
}

}