/*
 * Classe que representa a implementação do escravo.
 */
package br.inf.ufes.ppd.impl;

import br.inf.ufes.ppd.utils.Decrypt;
import br.inf.ufes.ppd.Guess;
import br.inf.ufes.ppd.Master;
import br.inf.ufes.ppd.Slave;
import br.inf.ufes.ppd.SlaveManager;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.jms.*;
import javax.json.*;
import javax.xml.bind.DatatypeConverter;

import com.sun.messaging.ConnectionConfiguration;

/**
 *
 * @author lucas
 */
public class SlaveImpl implements Slave {

	private final String dicFilename = "dictionary.txt";
	private static String slaveName;
	List<String> dictionary = readDictionary(dicFilename);

	private UUID id = java.util.UUID.randomUUID();

	public void setId(UUID id) {
		this.id = id;
	}

	public UUID getId() {
		return this.id;
	}

	private List<String> readDictionary(String filename) {
		List<String> dictionary = new ArrayList<>();

		try {
			FileReader file = new FileReader(filename);
			BufferedReader readFile = new BufferedReader(file);

			String line = readFile.readLine();

			while (line != null) {
				dictionary.add(line.replace("\n", "").replace(" ", ""));

				line = readFile.readLine(); //
			}

			file.close();
		} catch (IOException e) {
			System.err.printf("Erro na abertura do arquivo: %s.\n", e.getMessage());
		}

		return dictionary;
	}

	/*
	 * Retorna um vetor de bytes com o texto decriptado.
	 */
	private byte[] readDecryptedTextAsBytes(String filename) {

		Path fileLocation = Paths.get(filename);
		byte[] data = null;

		try {
			data = Files.readAllBytes(fileLocation);
		} catch (IOException ex) {
			Logger.getLogger(SlaveImpl.class.getName()).log(Level.SEVERE, null, ex);
		}

		return data;
	}

	// Retorna true se o knowntext estiver contido em text.
	private static boolean compareBytes(byte[] text, byte[] knowntext) {
		for (int i = 0; i < text.length - knowntext.length; i++) {
			for (int j = 0; j < knowntext.length; j++) {
				if (text[i + j] != knowntext[j]) {
					break;
				}
				if (j == knowntext.length - 1) {
					return true;
				}
			}
		}
		return false;
	}

	// Verifica se o knowtext está na mensagem descriptografada
	private boolean checkDecryptedText(String textFilename, byte[] knowntext) {
		if (checkFileExists(textFilename)) {
			byte[] decryptedText = readDecryptedTextAsBytes(textFilename);
			if (compareBytes(decryptedText, knowntext)) {
				return true;
			}

			deleteFile(textFilename);
		}

		return false;
	}

	// Verifica se o arquivo existe
	private boolean checkFileExists(String filename) {
		File file = new File(filename);

		return file.exists();
	}

	// Deleta o arquivo
	private void deleteFile(String filename) {
		File file = new File(filename);
		file.delete();
	}
	
	@Override
	public void sendGuesses(List<Guess> guesses, int attackNumber, 
			Queue guessesQueue, JMSContext context) {
		
		JsonBuilderFactory factory = Json.createBuilderFactory(null);
		
		JsonArrayBuilder jArrayBuilder = factory.createArrayBuilder();
		
		if (guesses.size() > 0) {
			for(Guess guess : guesses) {
				String key = guess.getKey();
				byte[] message = guess.getMessage();
				String messageBase64Encoded = DatatypeConverter.printBase64Binary(message);
				
				JsonObjectBuilder jObjBuilder = factory.createObjectBuilder()
						.add("key", key)
						.add("message", messageBase64Encoded);
				jArrayBuilder.add(jObjBuilder);
			}
		} else {
			JsonObjectBuilder jObjBuilder = factory.createObjectBuilder()
					.add("key", "")
					.add("message", "");
			jArrayBuilder.add(jObjBuilder);
		}
		
		JsonObject jBuiltObj = factory.createObjectBuilder()
				.add("attackNumber", Integer.toString(attackNumber))
				.add("slaveName", slaveName)
				.add("guesses", jArrayBuilder)
				.build();
		
		synchronized (guessesQueue) {
			JMSProducer guessProducer = context.createProducer();
			
			String content = jBuiltObj.toString();
			System.out.println("Content: " + content);
			TextMessage textMessage = context.createTextMessage();
			try {
				textMessage.setText(content);
			} catch (JMSException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			
			guessProducer.send(guessesQueue, textMessage);
		}
	}

	@Override
	public void startSubAttack(byte[] ciphertext, byte[] knowntext, long initialwordindex, long finalwordindex,
			int attackNumber, Queue guessesQueue, JMSContext context) throws RemoteException {

		Decrypt decrypt = new Decrypt();
		System.out.println("Iniciando o subattack...");
		List<Guess> guesses = new ArrayList<>();
		
		for (long index = initialwordindex; index <= finalwordindex; index++) {
			String key = dictionary.get((int) index);

			// Se a decriptação retornar erro, pula para a próxima palavra.
			if (!decrypt.decrypt(key, ciphertext)) {
				continue;
			}

			//	System.out.println("key " + key);
			String decryptedFilename = key + ".msg";

			// Verifica se o texto descriptografado possui a palavra conhecida.
			if (checkDecryptedText(decryptedFilename, knowntext)) {
				System.out.println("Decrypted filename: " + decryptedFilename);
				
				Guess guess = new Guess();
				
				byte[] message = readDecryptedTextAsBytes(decryptedFilename);
				
				guess.setKey(key);
				guess.setMessage(message);
				
				guesses.add(guess);
				
				System.out.println("Guess encontrado!");
			}
		}

		sendGuesses(guesses, attackNumber, guessesQueue, context);
		System.out.println("Fim do subattack");

	}

	public static void main(String[] args) {

		String host = (args.length < 1) ? "localhost" : args[0];
		//slaveName = args[1];
		slaveName = "escravo";
		JsonReaderFactory readerFactory = Json.createReaderFactory(null);

		try {
			Logger.getLogger("").setLevel(Level.INFO);

			com.sun.messaging.ConnectionFactory connectionFactory = new com.sun.messaging.ConnectionFactory();
			connectionFactory.setProperty(ConnectionConfiguration.imqAddressList, host + ":7676");
			connectionFactory.setProperty(ConnectionConfiguration.imqConsumerFlowLimitPrefetch, "false");

			Queue subAttackQueue = new com.sun.messaging.Queue("SubAttacksQueue");
			System.out.println("obtained SubAttacksQueue.");
			
			Queue guessesQueue = new com.sun.messaging.Queue("GuessesQueue");
			System.out.println("obtained GuessesQueue.");

			JMSContext context = connectionFactory.createContext();
			
			JMSConsumer subAttackConsumer = context.createConsumer(subAttackQueue);
			
			SlaveImpl slave = new SlaveImpl();

			while (true) {
				Message m = subAttackConsumer.receive();
				if (m instanceof TextMessage) {
					JsonReader jreader = readerFactory.createReader(new StringReader(((TextMessage) m).getText()));
					JsonObject jobj = jreader.readObject();

					int initialwordindex = Integer.parseInt(jobj.getString("initialwordindex"));
					int finalwordindex = Integer.parseInt(jobj.getString("finalwordindex"));
					byte[] ciphertext = DatatypeConverter.parseBase64Binary(jobj.getString("ciphertext"));
					byte[] knowntext = DatatypeConverter.parseBase64Binary(jobj.getString("knowntext"));
					int attackNumber = Integer.parseInt(jobj.getString("attackNumber"));

					System.out.println("initialIndex: " + initialwordindex);
					System.out.println("finalIndex: " + finalwordindex);
					
					slave.startSubAttack(ciphertext, knowntext, initialwordindex, finalwordindex, attackNumber, guessesQueue, context);
				} else {
					System.out.println("Fila vazia!");
				}

				//Thread.sleep(1000);
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
}
