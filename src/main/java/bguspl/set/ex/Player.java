package bguspl.set.ex;

import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.logging.Level;

import bguspl.set.Env;

/**
 * This class manages the players' threads and data
 *
 * @inv id >= 0
 * @inv score >= 0
 */
public class Player implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;

    /**
     * The game dealer
     */
    private final Dealer dealer;


    /**
     * The id of the player (starting from 0).
     */
    public final int id;

    /**
     * The thread representing the current player.
     */
    private Thread playerThread;

    /**
     * The thread of the AI (computer) player (an additional thread used to generate key presses).
     */
    private Thread aiThread;

    /**
     * True iff the player is human (not a computer player).
     */
    private final boolean human;

    /**
     * True iff game should be terminated due to an external event.
     */
    private volatile boolean terminate;

    /**
     * The current score of the player.
     */
    private int score;

    /*
     * The slots to be pressed
     */
    private final Queue<Integer> pendingSlots;

    /*
     * The time the player should be freezed until (in milliseconds)
     */
    private volatile long freezeUntil;

    /**
     * The class constructor.
     *
     * @param env    - the environment object.
     * @param dealer - the dealer object.
     * @param table  - the table object.
     * @param id     - the id of the player.
     * @param human  - true iff the player is a human player (i.e. input is provided manually, via the keyboard).
     */
    public Player(Env env, Dealer dealer, Table table, int id, boolean human) {
        this.env = env;
        this.dealer = dealer;
        this.table = table;
        this.id = id;
        this.human = human;
        
        this.pendingSlots = new LinkedList<Integer>();
        this.freezeUntil = Long.MAX_VALUE;
    }

    /**
     * The main player thread of each player starts here (main loop for the player thread).
     */
    @Override
    public void run() {
        System.out.println(""+id +" created");
        playerThread = Thread.currentThread();
        env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + " starting.");
        if (!human) createArtificialIntelligence();

        while (!terminate) {
            // Wait for key press
            try {
                synchronized (this) { wait(); }
            } catch (InterruptedException ignored) {}

            placeNextToken();
        }
        if (!human) try { aiThread.join(); } catch (InterruptedException ignored) {}
        env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * Creates an additional thread for an AI (computer) player. The main loop of this thread repeatedly generates
     * key presses. If the queue of key presses is full, the thread waits until it is not full.
     */
    private void createArtificialIntelligence() {
        // note: this is a very very smart AI (!)
        System.out.println(""+id +" ai created");
        aiThread = new Thread(() -> {
            env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + " starting.");
            while (!terminate) {
                Random rand = new Random();

                Integer[] tokens = table.getPlayerTokens(id);
                Integer keyPress = tokens[rand.nextInt(3)];
                if (keyPress == null)
                    keyPress = rand.nextInt(12);

                keyPressed(keyPress);
                try {
                    Thread.sleep(rand.nextInt(100));
                } catch (InterruptedException e) {}
            }
            env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + " terminated.");
        }, "computer-" + id);
        aiThread.start();
    }

    /**
     * Called when the game should be terminated due to an external event.
     */
    public void terminate() {
        terminate = true;
    }

    /**
     * This method is called when a key is pressed.
     *
     * @param slot - the slot corresponding to the key pressed.
     */
    public void keyPressed(int slot) {
        if (System.currentTimeMillis() >= freezeUntil) {
            pendingSlots.add(slot);
            synchronized (this) { notify(); }
        }
    }

    private void placeNextToken() {
        if (!pendingSlots.isEmpty()) {
            int slot = pendingSlots.remove();

            if (!table.placeToken(id, slot))
                table.removeToken(id, slot);

            // place third token?
            if (table.getNextFreeToken(id) == -1) {
                dealer.addClaim(id);
                synchronized (dealer) { dealer.notify(); }

                // wait for point or penalty
                try { synchronized (this) { wait(); } } catch (InterruptedException ignored) {}

                // clear key input queue
                pendingSlots.clear();
            }
        }
    }

    public long getFreezeUntil(){
        return freezeUntil;
    }

    public void setFreezeUntilToCurrentTime() {
        this.freezeUntil = System.currentTimeMillis();
    }

    public void setFreezeUntilToMaxValue() {
        this.freezeUntil = Long.MAX_VALUE;
    }

    /**
     * Award a point to a player and perform other related actions.
     *
     * @post - the player's score is increased by 1.
     * @post - the player's score is updated in the ui.
     */
    public void point() {
        freezeUntil = System.currentTimeMillis() + env.config.pointFreezeMillis;
        synchronized (this) { notify(); }

        env.ui.setScore(id, ++score);
        env.ui.setFreeze(id, env.config.pointFreezeMillis);
    }

    /**
     * Penalize a player and perform other related actions.
     */
    public void penalty() {
        freezeUntil = System.currentTimeMillis() + env.config.penaltyFreezeMillis;
        synchronized (this) { notify(); }

        env.ui.setFreeze(id, env.config.penaltyFreezeMillis);
    }

    public int getScore() {
        return score;
    }
}
