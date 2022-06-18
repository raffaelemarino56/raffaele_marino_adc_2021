package it.raffaelemarino.sudoku;

import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashSet;

import net.tomp2p.dht.FutureGet;
import net.tomp2p.dht.PeerBuilderDHT;
import net.tomp2p.dht.PeerDHT;
import net.tomp2p.futures.FutureBootstrap;
import net.tomp2p.futures.FutureDirect;
import net.tomp2p.p2p.Peer;
import net.tomp2p.p2p.PeerBuilder;
import net.tomp2p.peers.Number160;
import net.tomp2p.peers.PeerAddress;
import net.tomp2p.rpc.ObjectDataReply;
import net.tomp2p.storage.Data;

public class SudokuGameImpl implements SudokuGame{

	final private Peer peer;
	final private PeerDHT _dht;
	final private int DEFAULT_MASTER_PORT=4000;
	private Integer id;

	private ArrayList<String> listaGiochi=new ArrayList<String>();


	public SudokuGameImpl(int _id, String _master_peer, final MessageListenerImpl _listener) throws Exception {

		this.id=_id;

		peer= new PeerBuilder(Number160.createHash(_id)).ports(DEFAULT_MASTER_PORT+_id).start();
		_dht = new PeerBuilderDHT(peer).start();

		FutureBootstrap fb = peer.bootstrap().inetAddress(InetAddress.getByName(_master_peer)).ports(DEFAULT_MASTER_PORT).start();
		fb.awaitUninterruptibly();
		if(fb.isSuccess()) {
			peer.discover().peerAddress(fb.bootstrapTo().iterator().next()).start().awaitUninterruptibly();
		}else {
			throw new Exception("Error in master peer bootstrap.");
		}

		if (_listener!=null)
			this.peer.objectDataReply(new ObjectDataReply() {
				public Object reply(PeerAddress sender, Object request) throws Exception {
					return _listener.parseMessage(request);
				}
			});
	}

	//genera nuova partita dato un nome
	public Integer[][] generateNewSudoku(String _game_name) {
		// TODO Auto-generated method stub
		
		Integer[][] a = new Integer[9][9];

		try {

			FutureGet futureGet = _dht.get(Number160.createHash(_game_name)).start();
					futureGet.awaitUninterruptibly();
			//controllo se esiste il gioco con quel nome
			if (futureGet.isSuccess() && futureGet.isEmpty()) {
				
				CampoDiGioco gioco = new CampoDiGioco();
				//per _game_name crea il campo da gioco che do a new Data() ovviamente tutto l'oggetto
				_dht.put(Number160.createHash(_game_name)).data(new Data(gioco)).start().awaitUninterruptibly();
				//return della matrice che ho creato

				return a;
			}

		} catch (Exception e) {
			e.printStackTrace();
		}

		return a;
	}

	//entra in una partita (avvisa i giocatori in partita che sei entrato nella partita)
	public boolean join(String _game_name, String _nickname) {
		// TODO Auto-generated method stub

		try {
			FutureGet futureGet = _dht.get(Number160.createHash(_game_name)).start().awaitUninterruptibly();

			//controllo se esiste il gioco con quel nome
			if (futureGet.isSuccess() && !futureGet.isEmpty()) { 
				//recupero il gioco, che contiene il campo e i giocatori
				CampoDiGioco gioco = (CampoDiGioco) futureGet.dataMap().values().iterator().next().object();


				//controllo se sono gi� in gioco o gi� esiste quel nickcanme
				//potrebbe capitare un nuovo gioco con lo stesso nome di uno precedente gi� terminato, al quale il giocatore aveva gi� fatto
				//accesso, ecco perch� � bene controllare se nel gioco il giocatore � presente
				//avendo il giocatore una lista dei giochi ai quali ha partecipato potrebbe confondere
				if(gioco.isNickInGame(_nickname) || gioco.isPeerInGame(this.peer.peerAddress()))
					return false;


				Giocatore giocatore = new Giocatore(_nickname,this.peer.peerAddress(),id,0,gioco.getCampo_di_gioco_iniziale() );

				//devo aggiungere questo gioco alla lista dei giochi a cui il giocatore partecipa, perch� pu� partecipare a pi� giochi
				if(giocatore.addGiocoAGiocatore(gioco)) {
					//mi aggiungo alla lista giocatori di quel gioco
					gioco.aggiungiGiocatore(giocatore);
					this.listaGiochi.add(_game_name);

					//aggiorno lo stato
					_dht.put(Number160.createHash(_game_name)).data(new Data(gioco)).start().awaitUninterruptibly();

					//notifico a tutti i giocatori in quella partita l'accesso del nuovo giocatore
					for(Giocatore g : gioco.getGiocatori()) {
						FutureDirect futureDirect = _dht.peer().sendDirect(g.getPeerAddres()).object("giocatore" + _nickname + "� entrato in partita").start().awaitUninterruptibly();
					}
				}


			}else {
				return false;
			}
		}catch (Exception e) {
			// TODO: handle exception
		}

		return false;
	}



	//visualizza stato di una partita relativo al giocatore che ha chiamato questa operazione e ritorna il campo di gioco
	public Integer[][] getSudoku(String _game_name) {
		// TODO Auto-generated method stub
		try {
			FutureGet futureGet = _dht.get(Number160.createHash(_game_name)).start().awaitUninterruptibly();

			//verifico se esiste
			if (futureGet.isSuccess() && !futureGet.isEmpty()) { 
				CampoDiGioco gioco = (CampoDiGioco) futureGet.dataMap().values().iterator().next().object();

				//recupero le info del giocatore con id se partecipa al gioco
				Giocatore giocatore = gioco.getGiocatoreByPeer(this.peer.peerAddress());

				if(giocatore != null) {
					return giocatore.getGiocoGiocatore();
				}


			}
		}catch (Exception e) {
			// TODO: handle exception
		}

		return null;
	}

	//piazza numero in una partita (avvisa giocatori che hai piazzato il numero e il punteggio)
	public Integer placeNumber(String _game_name, int _i, int _j, int _number) {
		// TODO Auto-generated method stub

		try {
			FutureGet futureGet = _dht.get(Number160.createHash(_game_name)).start().awaitUninterruptibly();

			//verifico se esiste
			if (futureGet.isSuccess() && !futureGet.isEmpty()) { 
				CampoDiGioco gioco = (CampoDiGioco) futureGet.dataMap().values().iterator().next().object();


				//recupero il campo di gioco relativo al _game_name del giocatore con id se esiste
				Giocatore giocatore = gioco.getGiocatoreByPeer(this.peer.peerAddress());
				if(giocatore == null)
					return null;

				//piazza numero e ottieni il punteggio
				int punto=gioco.controllaNumeroPiazzato(_i, _j, _number);

				//aggiorna il campo di gioco se il giocatore ha messo il valore giusto
				if(punto==1)
					gioco.aggiornaCampoDiGioco(_i, _j, _number);

				//aggiorno punteggio giocatore
				if(punto!=0)
					giocatore.setPunteggio(giocatore.getPunteggio()+punto);

				//aggiorno lista giocatori nel gioco per via del nuovo punteggio del giocatore
				if(punto!=0)
					gioco.aggiornaListaGiocatori(giocatore);

				//aggiorno lo stato della partita
				_dht.put(Number160.createHash(_game_name)).data(new Data(gioco)).start().awaitUninterruptibly();


				//notifico a tutti i giocatori in quella partita che ho piazzato il numero e il mio punteggio
				for(Giocatore g : gioco.getGiocatori()) {
					FutureDirect futureDirect = _dht.peer().sendDirect(g.getPeerAddres()).object("giocatore" + giocatore.getNick() + "ha messo il numero" + _number +" in posizione i:"+_i+" j:"+_j+"ed ha punteggio:"+giocatore.getPunteggio()).start().awaitUninterruptibly();

					//se il gioco � finito lo notifico a tutti e mostro la scoreboard
					if(gioco.isFinish()) {
						FutureDirect futureDirect2= _dht.peer().sendDirect(g.getPeerAddres()).object("il gioco � finito!!!, questa � la scoreboard: "+gioco.getScoreboard()).start().awaitUninterruptibly();
					}
				}
				return punto;
			}
		}catch(Exception e) {
			// TODO: handle exception

		}
		return null;
	}



	//restituisce la score board
	public String getLeadboard(String _game_name){
		String s = null;
		try {


			FutureGet futureGet = _dht.get(Number160.createHash(_game_name)).start().awaitUninterruptibly();
			if (futureGet.isSuccess() && !futureGet.isEmpty()) {

				CampoDiGioco gioco = (CampoDiGioco) futureGet.dataMap().values().iterator().next().object();
				Giocatore g = gioco.getGiocatoreByPeer(this.peer.peerAddress());

				//se il giocatore partecipa alla partita allora pu� vedere la lead board altrimenti no
				if(gioco.isPeerInGame(this.peer.peerAddress())) {

					return gioco.getScoreboard();


				}else{
					return null;
				}


			}else {
				return null;
			}
		}catch (Exception e) {
			e.printStackTrace();
		}
		return s;

	}


	public int isTeerminated(String _game_name){
		try {
			FutureGet futureGet = _dht.get(Number160.createHash(_game_name)).start().awaitUninterruptibly();
			if (futureGet.isSuccess() && !futureGet.isEmpty()) {
				CampoDiGioco gioco = (CampoDiGioco) futureGet.dataMap().values().iterator().next().object();

				Giocatore g = gioco.getGiocatoreByPeer(this.peer.peerAddress());

				if(gioco.isPeerInGame(this.peer.peerAddress())) {
					if(gioco.isFinish()) {
						//sei in gioco ed � terminato
						return 1;
					}
				}else {
					//non sei in gioco non puoi sapere se il gioco � terminato o meno
					return 0;
				}


			}
		}catch (Exception e) {
			e.printStackTrace();
		}
		//c'� stato un errore
		return -1;
	}


	//leave da un solo gioco
	@SuppressWarnings("unchecked")
	public boolean leaveGame(String _game_name) {
		try {
			FutureGet futureGet = _dht.get(Number160.createHash(_game_name)).start().awaitUninterruptibly();
			if (futureGet.isSuccess() && !futureGet.isEmpty()) {

				CampoDiGioco gioco = (CampoDiGioco) futureGet.dataMap().values().iterator().next().object();

				Giocatore g = gioco.getGiocatoreByPeer(this.peer.peerAddress());

				if(gioco.isPeerInGame(this.peer.peerAddress())) {
					gioco.rimuoviGiocatore(g);
					g.removeGiocoDaGiocatore(gioco);
					//aggiorno lo stato della partita
					_dht.put(Number160.createHash(_game_name)).data(new Data(gioco)).start().awaitUninterruptibly();

					return true;
				}


			}
		}catch (Exception e) {
			e.printStackTrace();
		}
		return false;
	}


	//lascaire tutte le partite prima di lascaire la rete
	public boolean leveAllGames() {

		while(!this.listaGiochi.isEmpty()) {
			leaveGame(this.listaGiochi.get(0));
			this.listaGiochi.remove(0);

		}
		leaveNetwoks();

		return true;
	}

	public boolean leaveNetwoks() {

		_dht.peer().announceShutdown().start().awaitUninterruptibly();
		return true;

	}

}
