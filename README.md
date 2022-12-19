# Set-Game
we can add interupt (Cancellation pattern) like mani showed in the lacture like this:
-a new key press is always happening and added to the blocking queue of a player.
-when there is a set and dealer removes cards or reshaffle, we interrupt the thread (Interupt excetion is caught)
-in the catch claues we erase all the queued picks of a player

from lacture:
try {
    while (lst.size() == capacity)
        this.wait();
    lst.add(obj);
    this.notifyAll();
    } catch (IterruptedException e) {
        …
    }
