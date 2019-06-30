/*
 * Mestre especial. Usado para medição do overhead.
 */
package br.inf.ufes.ppd.utils.special;

import br.inf.ufes.ppd.Guess;
import br.inf.ufes.ppd.Master;
import br.inf.ufes.ppd.Slave;
import br.inf.ufes.ppd.utils.SlaveInfo;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Timer;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import java.util.Scanner;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.jms.*;

import com.sun.messaging.ConnectionConfiguration;

/**
 *
 * @author lucas
 */
public class MasterDump implements Master {

    private final String filename = "dictionary.txt";

    private int currentAttackId = 0;
    private UUID currentSlaveKey;
    //Armazena os escravos ativos
    private Map<UUID, Slave> slaves = new ConcurrentHashMap<>();
    //Armazena os nomes dos escravos
    private Map<UUID, String> slavesNames = new ConcurrentHashMap<>();
    //Armazena os guess por ataque
    private Map<Integer, List<Guess>> guesses = new ConcurrentHashMap<>();
    //Armazena as informações de cada subataque
    private Map<Integer, Map<UUID, SlaveInfo>> attacksInfo = new ConcurrentHashMap<>();
    //No caso de um ataque ter sido redividido, mapeia a qual ataque os novos 
    //ataque são referentes.
    private Map<Integer, List<Integer>> relatedAttacks = new ConcurrentHashMap<>();
    //Informa se o ataque já terminou
    private Map<Integer, Boolean> isAttackEnded = new ConcurrentHashMap<>();

    private Guess[] listToArray(List<Guess> list) {
        Guess[] array = new Guess[list.size()];

        for (int i = 0; i < list.size(); i++) {
            array[i] = list.get(i);
        }

        return array;
    }

    private List<Guess> mapToList(Map<Integer, List<Guess>> map) {
        Iterator entries = map.entrySet().iterator();
        List<Guess> guessesList = new ArrayList<>();

        while (entries.hasNext()) {
            Map.Entry entry = (Map.Entry) entries.next();

            for (Guess e : (List<Guess>) entry.getValue()) {
                guessesList.add(e);
            }
        }

        return guessesList;
    }

    private Guess[] mapToArray(Map<Integer, List<Guess>> map) {
        return listToArray(mapToList(map));
    }

    //Lê o dicionário
    private List<String> readDictionary(String filename) {
        List<String> dictionary = new ArrayList<>();

        try {
            FileReader file = new FileReader(filename);
            BufferedReader readFile = new BufferedReader(file);

            String line = readFile.readLine();

            while (line != null) {
                dictionary.add(line);

                line = readFile.readLine(); // lê da segunda até a última linha
            }

            file.close();
        } catch (IOException e) {
            System.err.printf("Erro na abertura do arquivo: %s.\n",
                    e.getMessage());
        }

        return dictionary;
    }

    //Adiciona as informações do escravo referentes a um subataque
    private void addSlaveInfo(int attackId, UUID slavekey, String slaveName, Slave s) {
        SlaveInfo si = new SlaveInfo(slavekey, slaveName, s);
        attacksInfo.get(attackId).put(slavekey, si).setTime(attacksInfo.get(attackId - 1).get(slavekey).getTime());

    }

    //Adiciona as informações sobre os escravos que fazem parte de um ataque
    private void addSlavesInfo(int attackId) {
        Map<UUID, SlaveInfo> slavesInfo = new ConcurrentHashMap<>();
        attacksInfo.put(attackId, slavesInfo);

        Iterator entries = slaves.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry entry = (Map.Entry) entries.next();

            Slave slave = (Slave) entry.getValue();
            UUID key = (UUID) entry.getKey();

            SlaveInfo si = new SlaveInfo(key, slavesNames.get(key), slave);
            si.setTime(System.nanoTime() / 1000000000.0);
            attacksInfo.get(attackId).put(key, si);
        }
    }
    
    /*
    @Override
    public void addSlave(Slave s, String slaveName, UUID slavekey)
            throws RemoteException {
        synchronized (slaves) {
            if (!slaves.containsKey(slavekey)) {
                SlaveInfo si = new SlaveInfo(slavekey, slaveName, s);
                slaves.put(slavekey, s);
                slavesNames.put(slavekey, slaveName);
                System.out.println("Slave de nome " + slaveName
                        + " foi registrado com sucesso!");
            }
        }
    }

    @Override
    public void removeSlave(UUID slaveKey) throws RemoteException {
        synchronized (slaves) {
            slaves.remove(slaveKey);
            slavesNames.remove(slaveKey);
        }
    }

    @Override
    public void foundGuess(UUID slaveKey, int attackNumber, long currentindex,
            Guess currentguess) throws RemoteException {
        try {
            int originalAttackId = relatedAttacks.get(attackNumber).get(0);
            guesses.get(originalAttackId).add(currentguess);

            System.out.println("--------------Guess-----------------------");
            System.out.println("Nome do escravo: " + slavesNames.get(slaveKey)
                    + " índice: " + currentindex + " | Chave candidata: "
                    + currentguess.getKey());
            System.out.println("------------------------------------------");

            attacksInfo.get(attackNumber).get(slaveKey).setCurrentIndex((int) currentindex);
        } catch (Exception er) {
            System.out.println(er);
        }

    }

    @Override
    public void checkpoint(UUID slaveKey, int attackNumber, long currentindex)
            throws RemoteException {

        attacksInfo.get(attackNumber).get(slaveKey).setCurrentIndex((int) currentindex);

        this.attacksInfo.get(attackNumber).get(slaveKey).setTime(System.nanoTime() / 1000000000.0);

        //Verifica se é o último checkpoint
        if (attacksInfo.get(attackNumber).get(slaveKey).getFinalIndex() == currentindex) {
            attacksInfo.get(attackNumber).get(slaveKey).setEnded(true);
            System.out.println("Último checkpoint!");
            System.out.println("Escravo " + attacksInfo.get(attackNumber).get(slaveKey).getNome() + " terminou");
            if (this.checkToNotify(attackNumber)) {
                int originalAttackId = relatedAttacks.get(attackNumber).get(0);
                synchronized (attacksInfo.get(originalAttackId)) {
                    synchronized (isAttackEnded.get(originalAttackId)) {
                        isAttackEnded.put(originalAttackId, Boolean.TRUE);
                    }

                    attacksInfo.get(originalAttackId).notify();
                }
            }
        } else {
            System.out.println("--------------------Checkpoint--------------------");
            System.out.println("Nome do escravo: " + attacksInfo.get(attackNumber).get(slaveKey).getNome()
                    + " índice: " + currentindex);
            System.out.println("---------------------------------------------------");
        }
    }
    */

    /*
        Verifica se o notify pode ser chamado.
     */
    /*
    private boolean checkToNotify(int checkpointAttackId) {
        Iterator attacks = attacksInfo.entrySet().iterator();

        for (Integer attackId : relatedAttacks.get(checkpointAttackId)) {
            Iterator entr = slaves.entrySet().iterator();

            while (entr.hasNext()) {
                Map.Entry entry = (Map.Entry) entr.next();
                UUID idd = (UUID) entry.getKey();

                if (!attacksInfo.get(attackId).get(idd).isEnded()) {
                    return false;
                }
            }
        }

        return true;
    }
    */


    public static void main(String[] args) {

        try {

            MasterDump obj = new MasterDump();
            Master objref = (Master) UnicastRemoteObject.exportObject(obj, 0);

            Registry registry = LocateRegistry.getRegistry("localhost");
            System.err.println("Server bindind");

            registry.rebind("mestre", objref);
            System.err.println("Server ready");
        } catch (Exception e) {
            System.err.println("Master exception: " + e.toString());
            e.printStackTrace();
        }
    }

    @Override
    public Guess[] attack(byte[] ciphertext, byte[] knowntext) throws RemoteException {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }

}
