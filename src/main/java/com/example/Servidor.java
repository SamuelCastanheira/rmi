package com.example;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class Servidor implements  IServidor {

    private static Servidor instancia;

    private static final ObjectMapper mapper = new ObjectMapper();

    private static final long TIMEOUT_MS = 120_000;

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

    @Override 
    public String getOponent(String jsonIn) throws RemoteException {

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

            throw new RemoteException(
                    "Informe ip, porta e protocolo no JSON."
            );
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
            throw new RemoteException(
                    "O protocolo deve ser tcp ou udp."
            );
        }

        Pedido pedido = new Pedido(ip, porta, protocolo);

        synchronized (lock) {

            // 2. Procurar um jogador que esteja aguardando
            // e utilize o mesmo protocolo.
            Iterator<Pedido> iterator = pendentes.iterator();

            while (iterator.hasNext()) {
                Pedido outro = iterator.next();

                if (outro.protocolo.equals(pedido.protocolo)) {

                    // Retira o oponente da lista de pendentes.
                    iterator.remove();

                    // O jogador que chegou primeiro aguardou.
                    // Ele receberá inicia = false.
                    outro.resposta = criarResposta(
                            pedido.ip,
                            pedido.porta,
                            false
                    );

                    // O jogador que acabou de chegar inicia.
                    String respostaAtual = criarResposta(
                            outro.ip,
                            outro.porta,
                            true
                    );

                    // Acorda a chamada RMI que estava aguardando.
                    lock.notifyAll();

                    System.out.println(
                            "Match: " + outro.ip + ":" + outro.porta
                                    + " <-> " + pedido.ip + ":" + pedido.porta
                                    + " (" + pedido.protocolo + ")"
                    );

                    return respostaAtual;
                }
            }

            // 3. Não existe oponente compatível.
            // Registra a requisição e aguarda um match.
            pendentes.add(pedido);

            System.out.println(
                    "Aguardando oponente: "
                            + pedido.ip + ":" + pedido.porta
                            + " (" + pedido.protocolo + ")"
            );

            long limite = System.currentTimeMillis() + TIMEOUT_MS;

            try {
                while (pedido.resposta == null) {

                    long restante =
                            limite - System.currentTimeMillis();

                    if (restante <= 0) {
                        pendentes.remove(pedido);

                        return criarErro(
                                "Tempo de espera por oponente esgotado."
                        );
                    }

                    lock.wait(restante);
                }

                return pedido.resposta;

            } catch (InterruptedException e) {

                pendentes.remove(pedido);

                Thread.currentThread().interrupt();

                throw new RemoteException(
                        "Espera pelo oponente interrompida.",
                        e
                );
            }
        }
    }

    private String criarResposta(
            String ip,
            int porta,
            boolean inicia
    ) throws RemoteException {

        ObjectNode resposta = mapper.createObjectNode();

        resposta.put("ip", ip);
        resposta.put("porta", porta);
        resposta.put("inicia", inicia);

        try {
            return mapper.writeValueAsString(resposta);
        } catch (Exception e) {
            throw new RemoteException(
                    "Erro ao gerar JSON da resposta.",
                    e
            );
        }
    }

    private String criarErro(String mensagem) throws RemoteException {

        ObjectNode resposta = mapper.createObjectNode();

        resposta.put("erro", mensagem);

        try {
            return mapper.writeValueAsString(resposta);
        } catch (Exception e) {
            throw new RemoteException(
                    "Erro ao gerar JSON de erro.",
                    e
            );
        }
    }

    public static void iniciar() throws Exception {

        instancia = new Servidor();

        IServidor stub = (IServidor)
                UnicastRemoteObject.exportObject(instancia, 0);

        Registry registry =
                LocateRegistry.createRegistry(Main.PORTA);

        registry.rebind(Main.NOME_SERVICO, stub);

        System.out.println(
                "Servidor de match pronto na porta " + Main.PORTA
        );
    }
}