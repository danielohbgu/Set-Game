package bguspl.set.ex;

import bguspl.set.Env;

import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.logging.Level;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * This class manages the dealer's threads and data
 */
public class Dealer implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;
    private final Player[] players;

    /**
     * The list of card ids that are left in the dealer's deck.
     */
    private final List<Integer> deck;

    /**
     * True iff game should be terminated due to an external event.
     */
    private volatile boolean terminate;

    /**
     * This thread is responsible for showing the current timer
     */
    private final Thread timer;

    /**
     * The queue of players that are claiming a set
     */
    private final BlockingQueue<Integer> setClaimsQueue;

    /**
     * The time when the dealer needs to reshuffle the deck due to turn timeout.
     */
    private long reshuffleTime = Long.MAX_VALUE;

    private volatile boolean timeout;

    public Dealer(Env env, Table table, Player[] players) {
        this.env = env;
        this.table = table;
        this.players = players;
        this.setClaimsQueue = new ArrayBlockingQueue<>(env.config.players, true);
        deck = IntStream.range(0, env.config.deckSize).boxed().collect(Collectors.toList());
        this.timeout = true;

        timer = new Thread(() -> {
            while(!Thread.interrupted()) {
                updateTimerDisplay(System.currentTimeMillis() >= reshuffleTime);
                for (int i = 0; i < players.length; i++)
                    env.ui.setFreeze(i, players[i].getFreezeUntil() - System.currentTimeMillis());
            }
        });
    }

    /**
     * The dealer thread starts here (main loop for the dealer thread).
     */
    @Override
    public void run() {
        env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + " starting.");
        timer.start();

        for(Player p : players)
            new Thread(p, "Player #" + p.id).start();

        while (!shouldFinish()) {
            // Fill empty table slots with cards from the deck
            placeAllCardsOnTable();

            // Wait for countdown timeout or player set claim
            timerLoop();

            // remove all cards from the table
            removeAllCardsFromTable();
        }
        announceWinners();
        env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * The inner loop of the dealer thread that runs as long as the countdown did not time out.
     */
    private void timerLoop() {
        while (!terminate && !timeout) {
            // Wait for countdown timeout or player set claim
            sleepUntilWokenOrTimeout();

            // check if player claimed a set and if so, remove the cards
            removeCardsFromTable();

            // place cards on the table if needed
            placeCardsOnTable();
        }
        System.out.println("OUT");
    }

    /**
     * Called when the game should be terminated due to an external event.
     */
    public void terminate() {
        terminate = true;
    }

    /**
     * Check if the game should be terminated or the game end conditions are met.
     *
     * @return true iff the game should be finished.
     */
    private boolean shouldFinish() {
        return terminate || env.util.findSets(deck, 1).size() == 0;
    }

    /**
     * Checks cards should be removed from the table and removes them.
     */
    private void removeCardsFromTable() {
        int playerId;
        try { playerId = setClaimsQueue.take(); }
        catch (InterruptedException ignored) {
            terminate();
            return;
        }

        int[] claimedSet = new int[3];
        Integer[] playerTokens = table.getPlayerTokens(playerId);

        for(int token = 0; token < claimedSet.length; token++)
            if (playerTokens[token] != null)
                claimedSet[token] = table.slotToCard[playerTokens[token]];
            else {
                // when a token of the claimed set was removed while waiting for it to be checked
                synchronized (players[playerId]) {
                    players[playerId].notify();
                }
                return;
            }

        if (env.util.testSet(claimedSet)){
            System.out.println("TOKENS: " + Arrays.toString(playerTokens));
            System.out.println("CARDS: " + Arrays.toString(claimedSet));
            System.out.println("ALL SLOTS TO CARDS: ");
            Arrays.stream(table.cardToSlot).filter(Objects::nonNull).forEach(slot -> System.out.println(slot + "->" + table.slotToCard[slot]));
            for (int card : claimedSet) {
                int slot = table.cardToSlot[card];
                // remove the card
                synchronized (this) {
                    for (Integer player : table.removeCard(slot)) 
                        // for each player that his token was removed
                        if (!player.equals(playerId))
                            // remove him from the set claims queue
                            if (setClaimsQueue.remove(player))
                                // notify him that he can continue placing tokens
                                synchronized (players[player]) {
                                    players[player].notify();
                                }
                }
            }
            players[playerId].point();
        }
        else
            players[playerId].penalty();
    }

    /**
     * adds a set claim for a specified player
     * @param player - the player that claimed a set.
     */
    public void addClaim(int player){
        try{ 
            setClaimsQueue.put(player); 
            setClaimsQueue.forEach(p -> System.out.print(p + Arrays.toString(table.getPlayerTokens(p)) + ", "));
            System.out.println();
        }
        catch (InterruptedException ignored) { terminate(); }
    }

    /**
     * Check if any cards can be removed from the deck and placed on the table.
     */
    private void placeCardsOnTable() {
        boolean placed = false;
        for (int slot = 0; slot < table.slotToCard.length; slot++)
            if (table.slotToCard[slot] == null && !deck.isEmpty()){
                table.placeCard(deck.remove(deck.size() - 1), slot);
                placed = true;
            }

        if(env.util.findSets(table.getAllCardsOnTable(),1).size()==0){
            System.out.println("No sets on table");
            removeAllCardsFromTable();
            placeAllCardsOnTable();
        }
        // reset the reshuffle time
        if (placed) {
            table.hints();
            reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis;
            System.out.println("==================================");
        }
    }

    private void placeAllCardsOnTable() {
        // shuffle the cards
        Collections.shuffle(deck);

        // place cards on the table
        placeCardsOnTable();
        
        // when finished placing cards - start the timer
        synchronized (this) { notify(); }

        // srart a new cycle
        timeout = false;

        // cancel freeze for all players
        for(Player p : players) p.setFreezeUntilToCurrentTime();
    }

    /**
     * Sleep for a fixed amount of time or until the thread is awakened for some purpose.
     */
    private void sleepUntilWokenOrTimeout() {
        try {
            synchronized (this) {
                wait();
            }
        }
        catch (InterruptedException ignored) {}
    }

    /**
     * Reset and/or update the countdown and the countdown display.
     */
    private void updateTimerDisplay(boolean reset) {
        if(reset) {
            // next reshuffle in turnTimeoutMillis milliseconds from now
            reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis;
            // time is over - timeout
            timeout = true;
            synchronized (this) {
                // notify the dealer about timeout (need to place new cards)
                 notify(); 
                 // stop the timer (until finished placing the new cards)
                 try { wait(); } catch (InterruptedException ignored) {}
            }
        }

        // update the timer in the ui
        env.ui.setCountdown(reshuffleTime - System.currentTimeMillis(), 
                            reshuffleTime - System.currentTimeMillis() <= env.config.turnTimeoutWarningMillis);
    }

    /**
     * Returns all the cards from the table to the deck.
     */
    private void removeAllCardsFromTable() {
        // block key press for all players
        for (Player player : players) player.setFreezeUntilToMaxValue();

        //removes all cards and token on them from the table and returns a list of these cards
        List<Integer> cards = table.removeAllCardsFromTable();
        deck.addAll(cards);
    }

    /**
     * Check who is/are the winner/s and displays them.
     */
    private void announceWinners() {
        // stop the timer
        timer.interrupt();
        // terminate all players
        for(Player p : players)
            p.terminate();

        // check who won
        int maxScore = -1;
        int winnerCount = 1;

        for(int i = 0; i < players.length; i++)
            if (players[i].getScore() > maxScore){
                maxScore = players[i].getScore();
                winnerCount = 1;
            }
            else if (players[i].getScore() == maxScore)
                winnerCount++;
        
        int[] winners = new int[winnerCount];
        int cur = 0;
        for (int i = 0; i < players.length; i++)
            if (players[i].getScore() == maxScore){
                winners[cur] = i;
                cur++;
            }
            
        // print winners on screen
        env.ui.announceWinner(winners);

    }

}
