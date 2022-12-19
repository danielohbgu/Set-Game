package bguspl.set.ex;

import bguspl.set.Env;

import java.util.*;
import java.util.stream.Collectors;

/**
 * This class contains the data that is visible to the player.
 *
 * @inv slotToCard[x] == y iff cardToSlot[y] == x
 */
public class Table {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Mapping between a slot and the card placed in it (null if none).
     */
    protected final Integer[] slotToCard; // card per slot (if any)

    /**
     * Mapping between a card and the slot it is in (null if none).
     */
    protected final Integer[] cardToSlot; // slot per card (if any)

    private final Integer[][] playersAndTokenToSlot; //slots with tokens for each player

    /**
     * Constructor for testing.
     *
     * @param env        - the game environment objects.
     * @param slotToCard - mapping between a slot and the card placed in it (null if none).
     * @param cardToSlot - mapping between a card and the slot it is in (null if none).
     */
    public Table(Env env, Integer[] slotToCard, Integer[] cardToSlot) {
        this.env = env;
        this.slotToCard = slotToCard;
        this.cardToSlot = cardToSlot;
        playersAndTokenToSlot = new Integer[env.config.players][3];
        for (int i = 0; i < env.config.players; i++)
            for (int j = 0; j < 3; j++)
                playersAndTokenToSlot[i][j] = null;
    }

    /**
     * Constructor for actual usage.
     *
     * @param env - the game environment objects.
     */
    public Table(Env env) {

        this(env, new Integer[env.config.tableSize], new Integer[env.config.deckSize]);
    }

    /**
     * This method prints all possible legal sets of cards that are currently on the table.
     */
    public void hints() {
        List<Integer> deck = Arrays.stream(slotToCard).filter(Objects::nonNull).collect(Collectors.toList());
        env.util.findSets(deck, Integer.MAX_VALUE).forEach(set -> {
            StringBuilder sb = new StringBuilder().append("Hint: Set found: ");
            List<Integer> slots = Arrays.stream(set).mapToObj(card -> cardToSlot[card]).sorted().collect(Collectors.toList());
            int[][] features = env.util.cardsToFeatures(set);
            System.out.println(sb.append("slots: ").append(slots).append(" features: ").append(Arrays.deepToString(features)));
        });
    }

    /**
     * Count the number of cards currently on the table.
     *
     * @return - the number of cards on the table.
     */
    public int countCards() {
        int cards = 0;
        for (Integer card : slotToCard)
            if (card != null)
                ++cards;
        return cards;
    }

    /**
     * Places a card on the table in a grid slot.
     * @param card - the card id to place in the slot.
     * @param slot - the slot in which the card should be placed.
     *
     * @post - the card placed is on the table, in the assigned slot.
     */
    public void placeCard(int card, int slot) {
        try {
            Thread.sleep(env.config.tableDelayMillis);
        } catch (InterruptedException ignored) {}

        cardToSlot[card] = slot;
        slotToCard[slot] = card;

        env.ui.placeCard(card, slot);
    }

    /**
     * Removes a card from a grid slot on the table.
     * @param slot - the slot from which to remove the card.
     */
    public Integer removeCard(int slot) {
        try {
            Thread.sleep(env.config.tableDelayMillis);
        } catch (InterruptedException ignored) {}

        //remove all tokens placed on the slot
        for (int player = 0; player < env.config.players; player++)
            for (int token = 0; token < 3; token++)
                if(playersAndTokenToSlot[player][token] != null && playersAndTokenToSlot[player][token]==slot) {
                    playersAndTokenToSlot[player][token]=null;
                    env.ui.removeToken(player,slot);
                }


        //remove the card
        Integer tempCard=slotToCard[slot];
        cardToSlot[slotToCard[slot]] = null;
        slotToCard[slot] = null;

        env.ui.removeCard(slot);

        return tempCard;
    }

    /**
     * Places a player token on a grid slot.
     * @param player - the player the token belongs to.
     * @param slot   - the slot on which to place the token.
     * @return       - true iff there is a free token that is placeable on the slot
     */
    public boolean placeToken(int player, int slot) {
        // check if a token is placed on the corresponding slot (return false)
        for (int token = 0; token < 3; token++)
            if(playersAndTokenToSlot[player][token] != null && playersAndTokenToSlot[player][token] == slot)
                return false;

        int freeToken = getNextFreeToken(player);
        if (freeToken != -1){
            playersAndTokenToSlot[player][freeToken] = slot;
            env.ui.placeToken(player, slot);
            return true;
        }

        return false;
    }

    /**
     * Removes a token of a player from a grid slot.
     * @param player - the player the token belongs to.
     * @param slot   - the slot from which to remove the token.
     * @return       - true iff a token was successfully removed.
     */
    public boolean removeToken(int player, int slot) {
        for (int token = 0; token < 3; token++){
            if(playersAndTokenToSlot[player][token] != null && playersAndTokenToSlot[player][token] == slot) {
                playersAndTokenToSlot[player][token] = null;
                env.ui.removeToken(player, slot);
                return true;
            }
        }
        return false;
    }


    /**
     * Removes all tokens from table (used when we do reshuffle)
     */
    public List<Integer> removeAllCardsFromTable(){
        List<Integer> cards=new ArrayList<>();
        for(int i=0; i<env.config.rows*env.config.columns; i++){
            cards.add(removeCard(i));
        }
        return cards;
    }

    /**
     * checks if a player has a free token, and returns it.
     * @param player - the player to check for.
     * @return       - the next free token if exists. else -1.
     */
    public int getNextFreeToken(int player){
        int freeToken = -1;
        for (int token = 0; token < 3; token++)
            if(freeToken == -1 && playersAndTokenToSlot[player][token] == null)
                freeToken = token;
        return freeToken;
    }

    /**
     * gets the tokens slots of the player
     * @param player - player to get tokens of.
     * @return       - all the slots of the player tokens
     */
    public Integer[] getPlayerTokensSlots(int player){
        return playersAndTokenToSlot[player];
    }
}
