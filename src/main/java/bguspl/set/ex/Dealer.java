package bguspl.set.ex;

import bguspl.set.Env;

import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
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

    private final BlockingQueue<Integer> setClaimsQueue;

    /**
     * The time when the dealer needs to reshuffle the deck due to turn timeout.
     */
    private long reshuffleTime = Long.MAX_VALUE;

    public Dealer(Env env, Table table, Player[] players) {
        this.env = env;
        this.table = table;
        this.players = players;
        this.setClaimsQueue = new ArrayBlockingQueue<>(1);
        deck = IntStream.range(0, env.config.deckSize).boxed().collect(Collectors.toList());

        timer = new Thread(() -> {
            while(!shouldFinish()) {
                updateTimerDisplay(System.currentTimeMillis() == reshuffleTime);
            }
        });

        // create and run player threads
        for (Player p : players) {
            Thread t = new Thread(p);
            t.start();
        }
    }

    /**
     * The dealer thread starts here (main loop for the dealer thread).
     */
    @Override
    public void run() {
        env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + " starting.");
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
     */
    private void removeCardsFromTable() {
        if (!setClaimsQueue.isEmpty()) {
            Integer playerId = setClaimsQueue.remove();
            Player p = players[playerId];
            int[] claimedSet = p.getTokensToSlots().stream().mapToInt(slot -> table.slotToCard[slot]).toArray();
            if (env.util.testSet(claimedSet)){
                for (int card : claimedSet) {
                    int slot = table.cardToSlot[card];
                    p.removeToken(slot);
                    table.removeCard(slot);
                }
                p.point();
            }
            else
                p.penalty();
        }
    }

    /**
     * Check if any cards can be removed from the deck and placed on the table.
     */
    private void placeCardsOnTable() {
        for (int slot = 0; slot < table.slotToCard.length; slot++)
            if (table.slotToCard[slot] == null && !deck.isEmpty())
                table.placeCard(deck.remove(deck.size() - 1), slot);
    }

    private void placeAllCardsOnTable() {
        // shuffle the cards
        Collections.shuffle(deck);

        placeCardsOnTable();

        // reset the reshuffle time
        reshuffleTime = System.currentTimeMillis() + 61 * 1000;

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
        if(reset)
            env.ui.setCountdown(61 * 1000, false);
        else
            env.ui.setCountdown(reshuffleTime - System.currentTimeMillis(), reshuffleTime - System.currentTimeMillis() <= env.config.turnTimeoutWarningMillis);
    }

    /**
     * Returns all the cards from the table to the deck.
     */
    private void removeAllCardsFromTable() {
        // TODO implement
    }

    /**
     * Check who is/are the winner/s and displays them.
     */
    private void announceWinners() {
        // TODO implement
    }
}
