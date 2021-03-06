/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package cliente;
import java.awt.Event;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;
import pacote.Pacote;

//bibliotecas para pode salvar o arquivo 
import java.io.*;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.sql.Time;
import java.util.Timer;
import pacote.Tread10secsCliente;

/**
 *
 * @author ismae
 */
public class Cliente {

    //coisas dos pacotes
    public static int tamanhoDeUmPacote = 661;
    public ThreadArrayCompartilhado ArrayDeRecebimento = new ThreadArrayCompartilhado();
    private ArrayList<byte[]> pedacoDoArq = new ArrayList<>();
    public ArrayList<Pacote> pacotes = new ArrayList<>();

    //coisas da janela
    public int base = 0;
    public int nextSeqNum = 0;
    public int tamanho_da_janela = 1;
    public int thress = 10000;

    //coisas do construtor
    public InetAddress IPAddress = null;
    private String hostName;
    private int porta;
    public DatagramSocket clienteUDP;
    private String caminho;

    //coisas do identificador da conexao
    public int id;
    public int meuNumeroDeSeq = 1;
    public int numSeqServer;
    public int portaDoServidor = 5555;

    public Cliente(String hostName, int porta, String caminho) {
        this.hostName = hostName;
        this.caminho = caminho;
        this.porta = porta;
        //pega o ip      
        try {
            IPAddress = InetAddress.getByName(hostName);
        } catch (UnknownHostException ex) {
            System.out.println("erro a tentar traduzir o nome em ip");
        }
        quebrarArquivo();
        try {
            this.clienteUDP = new DatagramSocket(porta);
        } catch (SocketException ex) {
            System.out.println("erro: não foi possivel abrir o datagramSocket no cliente na porta" + porta);
        }
    }

    public static void main(String[] args) {
        try {
            //criando a propria instancia da classe cliente        
            Cliente c = new Cliente("localhost", 10203, "C:\\Users\\ismae\\Google Drive\\ufc\\4 semestre\\redes\\projeto1.pdf");

            //se caso o arquivo seja maior que 100Mb eu saio do programa
            if (c.pedacoDoArq.size() * 512 > 100000000) {
                System.err.println("Tamanho do arquivo não suportado!\nEnviar arquivos de até 100 Mb");
                System.exit(0);
            }
            c.handShake(c);
            c.enviarArquivo(c);
            c.encerrarConexao(c);

            //quando chegar eu espero 2 sec para o ouvidor responder            
            Thread.sleep(2000);

            //que tenha chegado ou não eu fecho depois do 2 sec
            System.exit(0);

        } catch (IOException ex) {
            System.out.println("erro ao fazer handshake");
        } catch (InterruptedException ex) {
            System.out.println("Erro ao enviar o arquivo para o server");
        }

    }

    public void encerrarConexao(Cliente c) {
        //vou envviar o fin 
        //esperar o FynAck 
        //envia ack 

        Pacote pacote = new Pacote();
        //coloca o id de conexao
        pacote.setConnectionID(c.id);
        pacote.setSequenceNumber(c.meuNumeroDeSeq + 1);//coloca no pacote o numero de seq
        c.setMeuNumeroDeSeq(c.getMeuNumeroDeSeq() + 1);//aqui atualiza o meu numero de seq
        pacote.setFyn(true);

        Timer timeOut = new Timer();
        timeOut.schedule(new Thread_Envia_Pacote(c, pacote, "ENVIO DO FYN"), 0, 300);

        Pacote p = null;
        while (p == null) {
            p = c.ArrayDeRecebimento.acessarArray(2, null);
            if (p != null) {
                if (p.isAck() && p.isFyn()) {
                    break;
                } else {
                    p = null;
                }
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException ex) {

            }
        }
        timeOut.cancel();
        timeOut.purge();

        pacote = new Pacote();
        pacote.setConnectionID(c.id);
        pacote.setSequenceNumber(c.meuNumeroDeSeq + 1);//coloca no pacote o numero de seq
        c.setMeuNumeroDeSeq(c.getMeuNumeroDeSeq() + 1);//aqui atualiza o meu numero de seq
        pacote.setAck(true);

        timeOut = new Timer();
        timeOut.schedule(new Thread_Envia_Pacote(c, pacote, "ENVIO DO ACK DO FYN"), 0, 100);
        //vou criar uma tarefa que se o fyn não chegar em 2 sec eu fecho tudo 
        //está na no ouvidor
    }

    public void enviarArquivo(Cliente c) throws IOException, InterruptedException {
        System.out.println("%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%");
        System.out.println("                   ENVIANDO ARQUIVO                        ");
        

        //criei os pacotess 
        for (int i = 0; i < c.pedacoDoArq.size(); i++) {//se repete até acabar todos os pacotes             
            Pacote pacote = new Pacote();
            //coloca o id de conexao
            pacote.setConnectionID(c.id);

            c.setMeuNumeroDeSeq(c.getMeuNumeroDeSeq() + tamanhoDeUmPacote);

            pacote.setSequenceNumber(c.meuNumeroDeSeq);
            //pega os bytes e coloca no pacote
            pacote.setPayload(c.pedacoDoArq.get(i));
            c.pacotes.add(pacote);
        }

        //essa thread vai ficar ouvindo na porta, todos pacotes que chegarem serão encaminhados para o array    
        OuviServidor thread = new OuviServidor(c);
        boolean primeiraVez = true;//serve para saber se já enviou o ultimo pacote

        Timer timeOut = new Timer();
        Thread_Envia_Arquivo enviador;

        while (nextSeqNum < c.pacotes.size()) {
            timeOut = new Timer();

            if (base == 0) {
                nextSeqNum++;
                enviador = new Thread_Envia_Arquivo(base, nextSeqNum, c);
                //envia os pacote e enicia o temporizador
                timeOut.schedule(enviador, 0, 500);

            } else if (base == c.pacotes.size() - 1) {//NO ULTIMO CASO ELE N ENVIA O ULTIMO PACOTE                
                enviador = new Thread_Envia_Arquivo(base, nextSeqNum, c);
                //envia os pacote e enicia o temporizador
                timeOut = new Timer();
                timeOut.schedule(enviador, 0, 500);

            } else {
                //AUMENTE O NEXTSEQNUM
                int i = 0;
                //se nextseqnum + 1 for igual ao tamanho do arraylist então n entra
                while (i < tamanho_da_janela && (nextSeqNum + 1) < c.pacotes.size()) {
                    nextSeqNum++;
                    i++;
                }

                enviador = new Thread_Envia_Arquivo(base, nextSeqNum, c);
                //envia os pacote e enicia o temporizador
                timeOut = new Timer();
                timeOut.schedule(enviador, 0, 500);
            }

            while (base != nextSeqNum || base == c.pacotes.size() - 1) {

                Pacote p = c.ArrayDeRecebimento.acessarArray(2, null);

                //coloca a threadd aquiiii
                if (p == null) {
                    Thread.sleep(40);
                }
                if (p != null) {//se tiver ack                                       
                    if ((base == (c.pacotes.size() - 1)) && p.getAckNumber() > c.pacotes.get(base).getSequenceNumber()) {
                        //ultimo pacote confirmado PODE PARAR
                        break;
                    }

                    //aqui eu preciso atualizar a minha base de acordo com o ack, se for acumulativo ele vai ficar aumentando a base até o ack esperado
                    while (p.getAckNumber() > c.pacotes.get(base).getSequenceNumber() && p.getAckNumber() <= c.pacotes.get(c.pacotes.size() - 1).getSequenceNumber()) {
                        //SE O NUM DE ACK QUE CHEGOU FOR MAIOR DO QUE O QUE ESTÁ NA BASE EU AUMENTO A BASE E O ACK É MENOR QUE O DO ULTIMO PACOTE                         
                        //para cada pacote confirmado eu aumento a janela 

                        if (base == c.pacotes.size() - 1) {//SE A BASE CHEGAR NO ULTIMO PACOTE ENTÃO IREI PARAR PARA ENVIAR ELA 
                            break;
                        }

                        //verificação para partida lenta
                        if (tamanho_da_janela < thress) {
                            tamanho_da_janela++;
                        }

                        base++;
                    }

                    //AGORA SE A BASE ESTÁ NO ULTIMO PACOTE E É A PRIMEIRA VEZ QUE ACONTECE ENTÃO VOU ENVIAR ELA 
                    if (base == c.pacotes.size() - 1 && primeiraVez == true) {
                        primeiraVez = false;
                        timeOut.cancel();
                        timeOut.purge();

                        //aumentar o nextseqnum para sair do while de envio
                        nextSeqNum++;

                        enviador = new Thread_Envia_Arquivo(base, nextSeqNum, c);
                        timeOut = new Timer();
                        timeOut.schedule(enviador, 0, 500);

                    } else if (base == nextSeqNum) {
                        //PARA O TEMPORIZADOR
                        //porque agora eu vou atualizar a janela
                        timeOut.cancel();
                        timeOut.purge();

                    } else {//se não é porque tem pacote sem ser reconhecidos ainda então preciso reenviar, então dou start para enviar esperando o 0.5 sec

                        //REINICIA O TEMPORIZADOR
                        timeOut.cancel();
                        timeOut.purge();

                        enviador = new Thread_Envia_Arquivo(base, nextSeqNum, c);
                        timeOut = new Timer();
                        timeOut.schedule(enviador, 500, 500);

                    }

                }

            }

            //verificação para saber se está maior que o limiar 
            // ||      ------------------
            // ||    -----------------
            // ||-------------
            // || -
            // ||-________________________________
            if (tamanho_da_janela >= thress) {
                tamanho_da_janela++;
                thress = tamanho_da_janela;
            }

            //PARA O TIMEOUT PORQUE JÁ CHEGOU TODAS AS CONFIRMAÇÕES 
            timeOut.cancel();
            timeOut.purge();

            //limpa a lista de acks recebidos PORQUE JÁ FORAM TODOS CONFIRMADOS
            c.ArrayDeRecebimento.PacoteRecebidosDoServer.clear();
            
            System.out.println("ENVIADO:"+((base*100)/c.pacotes.size())+"%...");
        }

        //PARA O TIMEOUT DO ULTIMO PACOTE ENVIADO QUANDO ELE É CONFIRMADO 
        timeOut.cancel();
        timeOut.purge();
        
        System.out.println("%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%");
        System.out.println("                   TRANSFERENCIA CONCLUIDA                 ");                
    }

    public void handShake(Cliente c) throws IOException {
        if (c != null) {
            //vou começar enviando um pacote com o numero de sequencia, ack = 0, id=0 e SYN ativo
            Pacote pacoteDeSicro = new Pacote();
            pacoteDeSicro.setSyn(true);
            pacoteDeSicro.setSequenceNumber(meuNumeroDeSeq++);

            //enviei na thread que vai repetir até chegar realmente 
            Timer timeOut = new Timer();
            timeOut.schedule(new Thread_Envia_Pacote(c, pacoteDeSicro, "INICIANDO COMUNICAÇÃO"), 0, 2000);

            //vai esperar 10 secs por resposta
            Timer dezSecs = new Timer();
            dezSecs.schedule(new Tread10secsCliente(c), 10000, 10000);

            Pacote confirmacao;
            do {//enquanto não chegar a confirmação que eu quero eu repito e a thread que envia continua ligada
                //esperar o retorno 
                byte dataReceive[] = new byte[tamanhoDeUmPacote];
                DatagramPacket receive = new DatagramPacket(dataReceive, dataReceive.length);
                c.clienteUDP.receive(receive);

                confirmacao = converterByteParaPacote(dataReceive);

                // passo o numerro da porta que irei usar para enviar o arq
                portaDoServidor = receive.getPort();
                id = confirmacao.getConnectionID();
                numSeqServer = confirmacao.getSequenceNumber();
            } while (confirmacao != null && confirmacao.getSequenceNumber() != 4321);

            //ao sair eu desligo OS TIMES
            dezSecs.cancel();
            dezSecs.purge();

            timeOut.cancel();
            timeOut.purge();

            // envia o ack de confirmação
            Pacote ackDeSyn = new Pacote();
            ackDeSyn.setAck(true);
            ackDeSyn.setSequenceNumber(meuNumeroDeSeq);
            ackDeSyn.setAckNumber(++numSeqServer);
            ackDeSyn.setConnectionID(id);
            //coloco numa novo temporizador 
            timeOut = new Timer();
            timeOut.schedule(new Thread_Envia_Pacote(c, ackDeSyn, "RESPONDENDO ACK DO SYN"), 0, 2000);

            //vai esperar 10 secs por resposta
            dezSecs = new Timer();
            dezSecs.schedule(new Tread10secsCliente(c), 10000, 10000);

            Pacote pedidoDeDados;
            do {//espero a confirmação do server 
                byte dataReceive[] = new byte[tamanhoDeUmPacote];
                DatagramPacket receive = new DatagramPacket(dataReceive, dataReceive.length);
                c.clienteUDP.receive(receive);
                pedidoDeDados = converterByteParaPacote(dataReceive);
            } while (pedidoDeDados == null || pedidoDeDados.getAckNumber() != meuNumeroDeSeq + Cliente.tamanhoDeUmPacote);

            //chegou cancelo
            dezSecs.cancel();
            dezSecs.purge();

            timeOut.cancel();
            timeOut.purge();

            System.out.println("%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%");
            System.out.println("                   CONEXAO ESTABELCIDA            ");
        }
    }

    public void quebrarArquivo() {
        try {
            FileInputStream in = new FileInputStream(new File(caminho));
            BufferedInputStream bufferMusica = new BufferedInputStream(in);
            int n = 0;
            int i = 0;

            while (n != -1) {
                byte[] byteDoArquivo = new byte[512];
                n = bufferMusica.read(byteDoArquivo);
                pedacoDoArq.add(byteDoArquivo);
                i++;
            }
        } catch (FileNotFoundException ex) {
            System.out.println("Erro ao pegar o arquivo" + ex);
        } catch (IOException ex) {
            System.out.println("Erro IO" + ex);
        }

    }

    private static Pacote converterByteParaPacote(byte[] pacote) {

        try {
            ByteArrayInputStream bao = new ByteArrayInputStream(pacote);
            ObjectInputStream ous;
            ous = new ObjectInputStream(bao);
            return (Pacote) ous.readObject();

        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } catch (ClassNotFoundException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

        return null;
    }

    private static byte[] converterPacoteEmByte(Pacote pkt) {
        try {
            //cria um  array de byte  que irei passar para o objectOutput para retornar o byte[] , 
            //o pacote tem q implementar o Serializable
            ByteArrayOutputStream bao = new ByteArrayOutputStream();
            ObjectOutputStream ous;
            ous = new ObjectOutputStream(bao);
            ous.writeObject(pkt);
            return bao.toByteArray();
        } catch (IOException e) {
            // TODO Auto-generated catch block
            System.out.println("erro ao converte pacote em byte");
            e.printStackTrace();
        }

        return null;
    }

    public ArrayList<byte[]> getPedacoDoArq() {
        return pedacoDoArq;
    }

    public void setPedacoDoArq(ArrayList<byte[]> pedacoDoArq) {
        this.pedacoDoArq = pedacoDoArq;
    }

    public ArrayList<Pacote> getPacotes() {
        return pacotes;
    }

    public void setPacotes(ArrayList<Pacote> pacotes) {
        this.pacotes = pacotes;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public int getMeuNumeroDeSeq() {
        return meuNumeroDeSeq;
    }

    public void setMeuNumeroDeSeq(int meuNumeroDeSeq) {
        this.meuNumeroDeSeq = meuNumeroDeSeq;
    }

    public int getNumSeqServer() {
        return numSeqServer;
    }

    public void setNumSeqServer(int numSeqServer) {
        this.numSeqServer = numSeqServer;
    }

    public int getPortaDoServidor() {
        return portaDoServidor;
    }

    public void setPortaDoServidor(int portaDoServidor) {
        this.portaDoServidor = portaDoServidor;
    }

    public String getHostName() {
        return hostName;
    }

    public void setHostName(String hostName) {
        this.hostName = hostName;
    }

    public int getPorta() {
        return porta;
    }

    public void setPorta(int porta) {
        this.porta = porta;
    }

    public String getCaminho() {
        return caminho;
    }

    public void setCaminho(String caminho) {
        this.caminho = caminho;
    }

    public DatagramSocket getClienteUDP() {
        return clienteUDP;
    }

    public void setClienteUDP(DatagramSocket clienteUDP) {
        this.clienteUDP = clienteUDP;
    }

    public void finalizarTudo() {
        System.exit(0);
    }

}
