package bguspl.set.ex;

import bguspl.set.Env;

import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
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

    private final Queue<Integer> setClaimsQueue;

    /**
     * The time when the dealer needs to reshuffle the deck due to turn timeout.
     */
    private long reshuffleTime = Long.MAX_VALUE;

    private final Object timerLock;

    public Dealer(Env env, Table table, Player[] players) {
        this.env = env;
        this.table = table;
        this.players = players;
        this.setClaimsQueue = new LinkedList<>();
        deck = IntStream.range(0, env.config.deckSize).boxed().collect(Collectors.toList());
        this.timerLock = new Object();

        timer = new Thread(() -> {
            while(!shouldFinish()) {
                synchronized (timerLock) {
                    if (System.currentTimeMillis() >= reshuffleTime) {
                        try {
                                wait();

                        } catch (InterruptedException ignored) {
                        }
                    }
                    updateTimerDisplay(System.currentTimeMillis() >= reshuffleTime);
                    for (int i = 0; i < players.length; i++) {
                        env.ui.setFreeze(i, players[i].getFreezeUntil() - System.currentTimeMillis());
                    }
                }
            }
        });
    }

    /**
     * The dealer thread starts here (main loop for the dealer thread).
     */
    @Override
    public void run() {
        env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + " starting.");
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
        while (!terminate && System.currentTimeMillis() < reshuffleTime) {
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
        // TODO implement
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
     * @throws UnsupportedOperationException - thrown iff the claimed set contains less than 3 cards
     */
    private void removeCardsFromTable() throws UnsupportedOperationException {
        if (!setClaimsQueue.isEmpty()) {
            Integer playerId = setClaimsQueue.remove();
            Player p = players[playerId];
            int[] claimedSet = new int[3];
            Integer[] playerTokens = table.getPlayerTokensSlots(p.id);

            for(int token = 0; token < claimedSet.length; token++)
                if (playerTokens[token] != null)
                    claimedSet[token] = table.slotToCard[playerTokens[token]];
                else
                    throw new UnsupportedOperationException("WTF?!");

            if (env.util.testSet(claimedSet)){
                for (int card : claimedSet) {
                    int slot = table.cardToSlot[card];
                    table.removeCard(slot);
                    table.removeToken(p.id, slot);
                }
                p.point();

            }
            else
                p.penalty();
        }
    }

    /**
     * adds a set claim for a specified player
     * @param player - the player that claimed a set.
     */
    public synchronized void addClaim(int player){
        setClaimsQueue.add(player);
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

        // reset the reshuffle time
        if (placed) {
            reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis;
            synchronized (this) { notify(); }
        }
    }

    private void placeAllCardsOnTable() {
        // sync!!!!
        // shuffle the cards
        Collections.shuffle(deck);

        placeCardsOnTable();

        if (!timer.isAlive())
            timer.start();
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
            reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis;
            synchronized (this) { notify(); }
        }
        env.ui.setCountdown(reshuffleTime - System.currentTimeMillis(), reshuffleTime - System.currentTimeMillis() <= env.config.turnTimeoutWarningMillis);
    }

    /**
     * Returns all the cards from the table to the deck.
     */
    private void removeAllCardsFromTable() {
        //removes all cards and token on them from the table and returns a list of these cards
        List<Integer> cards = table.removeAllCardsFromTable();
        deck.addAll(cards);
    }

    /**
     * Check who is/are the winner/s and displays them.
     */
    private void announceWinners() {
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
        
        env.ui.announceWinner(winners);

    }
}
