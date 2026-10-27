// ===================== Servidor.java =====================
package com.example;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.rmi.NoSuchObjectException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class Servidor implements IServidor {

    private static Servidor instancia;
    private static Registry registry;

    private static final ObjectMapper mapper = new ObjectMapper();

    // Tempo máximo de espera por oponente (apenas o match).
    private static final long TIMEOUT_MS = 60_000;

    // Tempo máximo que o servidor RMI fica vivo sem atividade.
    private static final long TIMEOUT_MS_GLOBAL = 5 * 60_000;

    // Instante limite (ms) em que o servidor deve encerrar.
    private static volatile long timeout_global;

    // Garante que o encerramento aconteça uma única vez.
    private static final AtomicBoolean encerrando = new AtomicBoolean(false);

    // Mantém o main vivo até o servidor ser encerrado.
    private static final CountDownLatch fim = new CountDownLatch(1);

    private static class Pedido {
        String ip;
        int porta;
        String protocolo;
        String resposta;

        Pedido(String ip, int porta, String protocolo) {
            this.ip = ip;
            this.porta = porta;
            this.protocolo = protocolo;
        }
    }

    private final Object lock = new Object();

    private final List<Pedido> pendentes = new ArrayList<>();

    // Bloqueia o main até o servidor ser encerrado.
    public static void aguardar() throws InterruptedException {
        fim.await();
    }

    private static void atualizarTimeoutGlobal() {
        timeout_global = System.currentTimeMillis() + TIMEOUT_MS_GLOBAL;
    }

    @Override
    public String getOponent(String jsonIn) throws RemoteException {

        atualizarTimeoutGlobal();

        // 1. Interpretar e validar o JSON recebido.
        JsonNode dados;

        try {
            dados = mapper.readTree(jsonIn);
        } catch (Exception e) {
            throw new RemoteException("JSON de entrada invalido.", e);
        }

        if (dados == null
                || !dados.hasNonNull("ip")
                || !dados.hasNonNull("porta")
                || !dados.hasNonNull("protocolo")
                || !dados.get("ip").isTextual()
                || !dados.get("porta").canConvertToInt()
                || !dados.get("protocolo").isTextual()) {

            throw new RemoteException("Informe ip, porta e protocolo no JSON.");
        }

        String ip = dados.get("ip").asText().trim();
        int porta = dados.get("porta").asInt();
        String protocolo = dados.get("protocolo")
                .asText()
                .trim()
                .toLowerCase();

        if (ip.isEmpty()) {
            throw new RemoteException("IP invalido.");
        }

        if (porta < 1 || porta > 65535) {
            throw new RemoteException("Porta invalida.");
        }

        if (!protocolo.equals("tcp") && !protocolo.equals("udp")) {
            throw new RemoteException("O protocolo deve ser tcp ou udp.");
        }

        Pedido pedido = new Pedido(ip, porta, protocolo);

        synchronized (lock) {

            // 2. Procurar um jogador aguardando com o mesmo protocolo.
            Iterator<Pedido> iterator = pendentes.iterator();

            while (iterator.hasNext()) {
                Pedido outro = iterator.next();

                // Evita parear o jogador com ele mesmo.
                boolean mesmoJogador = outro.ip.equals(pedido.ip)
                        && outro.porta == pedido.porta;

                if (outro.protocolo.equals(pedido.protocolo) && !mesmoJogador) {

                    iterator.remove();

                    // Quem chegou primeiro aguardou: inicia = false.
                    outro.resposta = criarResposta(
                            pedido.ip, pedido.porta, false);

                    // Quem acabou de chegar: inicia = true.
                    String respostaAtual = criarResposta(
                            outro.ip, outro.porta, true);

                    lock.notifyAll();

                    atualizarTimeoutGlobal();

                    System.out.println(
                            "Match: " + outro.ip + ":" + outro.porta
                                    + " <-> " + pedido.ip + ":" + pedido.porta
                                    + " (" + pedido.protocolo + ")");

                    return respostaAtual;
                }
            }

            // 3. Sem oponente compatível: registra e aguarda.
            pendentes.add(pedido);

            System.out.println(
                    "Aguardando oponente: "
                            + pedido.ip + ":" + pedido.porta
                            + " (" + pedido.protocolo + ")");

            long limite = System.currentTimeMillis() + TIMEOUT_MS;

            try {
                while (pedido.resposta == null) {

                    long restante = limite - System.currentTimeMillis();

                    if (restante <= 0) {
                        pendentes.remove(pedido);

                        System.out.println(
                                "Timeout de match: "
                                        + pedido.ip + ":" + pedido.porta);

                        return criarErro(
                                "Tempo de espera por oponente esgotado.");
                    }

                    lock.wait(restante);
                }

                return pedido.resposta;

            } catch (InterruptedException e) {
                pendentes.remove(pedido);
                Thread.currentThread().interrupt();
                throw new RemoteException(
                        "Espera pelo oponente interrompida.", e);
            }
        }
    }

    private String criarResposta(String ip, int porta, boolean inicia)
            throws RemoteException {

        ObjectNode resposta = mapper.createObjectNode();

        resposta.put("ip", ip);
        resposta.put("porta", porta);
        resposta.put("inicia", inicia);

        try {
            return mapper.writeValueAsString(resposta);
        } catch (Exception e) {
            throw new RemoteException("Erro ao gerar JSON da resposta.", e);
        }
    }

    private String criarErro(String mensagem) throws RemoteException {

        ObjectNode resposta = mapper.createObjectNode();

        resposta.put("erro", mensagem);

        try {
            return mapper.writeValueAsString(resposta);
        } catch (Exception e) {
            throw new RemoteException("Erro ao gerar JSON de erro.", e);
        }
    }

    // Thread que vigia o tempo de vida do servidor.
    private static void iniciarVigia() {
        Thread vigia = new Thread(() -> {
            try {
                while (!encerrando.get()) {

                    long restante =
                            timeout_global - System.currentTimeMillis();

                    if (restante <= 0) {
                        System.out.println(
                                "Timeout global atingido. Encerrando servidor...");
                        encerrar();
                        System.exit(0);
                        return;
                    }

                    Thread.sleep(Math.min(Math.max(restante, 1), 1000));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "vigia-timeout-global");

        vigia.setDaemon(true);
        vigia.start();
    }

    private static String descobrirIp() {
        try (DatagramSocket s = new DatagramSocket()) {
            s.connect(InetAddress.getByName("8.8.8.8"), 80); // não envia nada
            return s.getLocalAddress().getHostAddress();
        } catch (Exception e) {
            try {
                return InetAddress.getLocalHost().getHostAddress();
            } catch (Exception e2) {
                return Main.IP_SERVER;
            }
        }
    }

    public static void iniciar() throws Exception {

        String ip = descobrirIp();
        System.setProperty("java.rmi.server.hostname", ip);

        instancia = new Servidor();

        IServidor stub = (IServidor)
                UnicastRemoteObject.exportObject(instancia, 0);

        registry = LocateRegistry.createRegistry(Main.PORTA);

        registry.rebind(Main.NOME_SERVICO, stub);

        // Ctrl+C: encerra o RMI de forma limpa.
        Runtime.getRuntime().addShutdownHook(
                new Thread(Servidor::encerrar, "shutdown-servidor"));

        atualizarTimeoutGlobal();
        iniciarVigia();

        System.out.println("Servidor de match pronto no IP " + ip);
        System.out.println("Servidor de match pronto na porta " + Main.PORTA);
        System.out.println("Timeout de match: " + (TIMEOUT_MS / 1000) + " s");
        System.out.println("Timeout global: " + (TIMEOUT_MS_GLOBAL / 1000)
                + " s (renovado a cada atividade)");
    }

    public static void encerrar() {

        // Só executa na primeira vez (vigia e Ctrl+C podem coincidir).
        if (!encerrando.compareAndSet(false, true)) {
            return;
        }

        try {
            if (registry != null) {
                registry.unbind(Main.NOME_SERVICO);
            }
        } catch (Exception ignored) {}

        try {
            if (instancia != null) {
                UnicastRemoteObject.unexportObject(instancia, true);
            }
        } catch (NoSuchObjectException ignored) {}

        try {
            if (registry != null) {
                UnicastRemoteObject.unexportObject(registry, true);
            }
        } catch (NoSuchObjectException ignored) {}

        System.out.println("Servidor RMI encerrado.");

        // Libera o main (Servidor.aguardar()).
        fim.countDown();
    }
}