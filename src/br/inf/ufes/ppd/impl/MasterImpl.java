/*
 * Classe representando a implementação do Mestre.
 */
package br.inf.ufes.ppd.impl;

import br.inf.ufes.ppd.Guess;
import br.inf.ufes.ppd.Master;
import br.inf.ufes.ppd.Slave;
import br.inf.ufes.ppd.utils.SlaveInfo;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.StringReader;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Timer;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.jms.JMSConsumer;
import javax.jms.JMSContext;
import javax.jms.JMSException;
import javax.jms.JMSProducer;
import javax.jms.Message;
import javax.jms.Queue;
import javax.jms.TextMessage;

import javax.json.*;
import javax.xml.bind.DatatypeConverter;

import com.sun.messaging.ConnectionConfiguration;

/**
 *
 * @author lucas
 */
public class MasterImpl implements Master {

	private final String filename = "dictionary.txt";

	private int currentattackNumber = 0;
	private static int amountPerSubAttack;
	// Armazena os guess por ataque
	private Map<Integer, List<Guess>> guesses = new ConcurrentHashMap<>();
	private Map<Integer, Integer> remainingSubAttacks = new ConcurrentHashMap<>();

	private static Queue subAttacksQueue;
	private static Queue guessesQueue;
	private static JMSContext context;
	private static JMSConsumer guessConsumer;
	private static JMSProducer subAttackProducer;

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

	// Lê o dicionário
	private List<String> readDictionary(String filename) {
		List<String> dictionary = new ArrayList<>();

		try {
			FileReader file = new FileReader(filename);
			BufferedReader readFile = new BufferedReader(file);

			String line = readFile.readLine();

			while (line != null) {
				dictionary.add(line);

				line = readFile.readLine();
			}

			file.close();
		} catch (IOException e) {
			System.err.printf("Erro na abertura do arquivo: %s.\n", e.getMessage());
		}

		return dictionary;
	}

	@Override
	public Guess[] attack(byte[] ciphertext, byte[] knowntext) throws RemoteException {

		System.out.println("Iniciando ataque!");

		List<String> dictionary = readDictionary(filename);
		JsonBuilderFactory jBuilderFactory = Json.createBuilderFactory(null);
		
		String cipherTextBase64Encoded = DatatypeConverter.printBase64Binary(ciphertext);
		String knownTextBase64Encoded = DatatypeConverter.printBase64Binary(knowntext);

		int attackNumber = this.currentattackNumber;
		this.currentattackNumber++;
		
		if(dictionary.size() % amountPerSubAttack == 0) {
			remainingSubAttacks.put(attackNumber, dictionary.size() / amountPerSubAttack);
		} else {
			remainingSubAttacks.put(attackNumber, dictionary.size() / amountPerSubAttack + 1);
		}

		int currentIndex = 0;
		int initialwordindex;
		int finalwordindex;

		while (currentIndex < dictionary.size()) {
			initialwordindex = currentIndex;
			finalwordindex = initialwordindex + amountPerSubAttack - 1;

			if (finalwordindex >= dictionary.size()) {
				finalwordindex = dictionary.size() - 1;
			}

			JsonArray jarray = jBuilderFactory.createArrayBuilder()
					.add(jBuilderFactory.createObjectBuilder()
							.add("initialwordindex", Integer.toString(initialwordindex))
							.add("finalwordindex", Integer.toString(finalwordindex))
							.add("ciphertext", cipherTextBase64Encoded)
							.add("knowntext", knownTextBase64Encoded)
							.add("attackNumber", Integer.toString(attackNumber)))
					.build();

			String content = jarray.toString();
			TextMessage textMessage = context.createTextMessage();
			try {
				textMessage.setText(content);
			} catch (JMSException e) {
				e.printStackTrace();
			}
			
			synchronized (subAttackProducer) {
				subAttackProducer.send(subAttacksQueue, textMessage);
			}
			
			currentIndex += amountPerSubAttack;
		}
		
		List<Guess> Lguesses = new ArrayList<>();
		guesses.put(attackNumber, Lguesses);
		
		try {
			JsonReaderFactory jReaderFactory = Json.createReaderFactory(null);
			
			while(remainingSubAttacks.get(attackNumber) > 0) {
				Message m;
				
				synchronized (guessConsumer) {
					m = guessConsumer.receive();
				}
				
				if (m instanceof TextMessage) {
					JsonReader jreader = jReaderFactory.createReader(new StringReader(((TextMessage) m).getText()));
					JsonObject jobj = jreader.readObject();
					
					int guessAttackNumber = Integer.parseInt(jobj.getString("attackNumber"));
					String slaveName = jobj.getString("slaveName");
					JsonArray jguesses = jobj.get("guesses").asJsonArray();
					
					/*
					System.out.println("Guess attack number: " + guessAttackNumber);
					System.out.println("Attack number: " + attackNumber);
					System.out.println("Remaining attacks: " + remainingSubAttacks.get(guessAttackNumber));
					*/
					
					if (jguesses.size() == 1) {
						JsonObject jguess = jguesses.get(0).asJsonObject();
						
						String key = jguess.getString("key");
						
						if (!key.equals("")) {
							byte[] message = DatatypeConverter.parseBase64Binary(jguess.getString("message"));
							
							Guess guess = new Guess();
							guess.setKey(key);
							guess.setMessage(message);
							
							System.out.println("Chave candidata: " + key + " - Encontrada pelo escravo: " + slaveName);
							
							guesses.get(guessAttackNumber).add(guess);
						}
					} else {
						for (int i = 0; i < jguesses.size(); i++) {
							JsonObject jguess = jguesses.get(i).asJsonObject();
							
							String key = jguess.getString("key");
							byte[] message = DatatypeConverter.parseBase64Binary(jguess.getString("message"));
							
							Guess guess = new Guess();
							guess.setKey(key);
							guess.setMessage(message);
							
							System.out.println("Key: " + key);
							
							guesses.get(guessAttackNumber).add(guess);
						}
					}
					
					System.out.println("Fim de um subattack...");
						
					remainingSubAttacks.put(guessAttackNumber, remainingSubAttacks.get(guessAttackNumber) - 1);
				}
				
				//Thread.sleep(1000);
			}
		} catch (Exception e) {
			e.printStackTrace();
		}

		System.out.println("Ataque terminado!");
		return listToArray(guesses.get(attackNumber));
	}

	public static void main(String[] args) {

		String host = (args.length < 1) ? "127.0.0.1" : args[0];
		amountPerSubAttack = 1000;

		try (Scanner s = new Scanner(System.in)) {
			Logger.getLogger("").setLevel(Level.SEVERE);

			System.out.println("obtaining connection factory...");
			com.sun.messaging.ConnectionFactory connectionFactory = new com.sun.messaging.ConnectionFactory();
			connectionFactory.setProperty(ConnectionConfiguration.imqAddressList, host + ":7676");
			System.out.println("obtained connection factory.");

			System.out.println("obtaining SubAttacksQueue queue...");
			subAttacksQueue = new com.sun.messaging.Queue("SubAttacksQueue");
			System.out.println("obtained queue.");
			
			System.out.println("obtaining GuessesQueue queue...");
			guessesQueue = new com.sun.messaging.Queue("GuessesQueue");
			System.out.println("obtained queue.");
			
			context = connectionFactory.createContext();
			subAttackProducer = context.createProducer();
			guessConsumer = context.createConsumer(guessesQueue);

			MasterImpl obj = new MasterImpl();
			Master objref = (Master) UnicastRemoteObject.exportObject(obj, 0);

			Registry registry = LocateRegistry.getRegistry("localhost");
			System.err.println("Server bindind");

			registry.rebind("mestre", objref);
			System.err.println("Server ready");
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

}
