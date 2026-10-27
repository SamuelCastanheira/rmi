// ===================== Main.java =====================
package com.example;

public class Main {

    public static final int PORTA = 9816;
    public static final String NOME_SERVICO = "JogoDaVelha";
    public static final String IP_SERVER = "152.92.236.16";
    public static final String GRUPO_MULTICAST = "225.1.1.16";
    public static final int PORTA_MULTICAST = 8816;

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("Uso:");
            System.out.println("  servidor");
            System.out.println("  jogador [ipServidor] [portaServidor]");
            System.out.println("  visualizador");
            return;
        }

        switch (args[0]) {
            case "servidor" -> {
                Servidor.iniciar();
                System.out.println("Servidor aguardando jogadores...");
                Servidor.aguardar();
            }

            case "jogador" -> {
                String ipServer = IP_SERVER;
                int portServer = PORTA;

                if (args.length > 1) {
                    ipServer = args[1];
                }
                if (args.length > 2) {
                    portServer = Integer.parseInt(args[2]);
                }

                new Jogador().iniciar(ipServer, portServer);
            }

            case "visualizador" -> new Visualizador().iniciar();

            default -> System.out.println(
                    "Argumento inválido. Use 'servidor', 'jogador' ou 'visualizador'.");
        }
    }
}