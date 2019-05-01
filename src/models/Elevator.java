package models;

import exceptions.InvalidStateException;
import exceptions.InvalidValueException;
import gui.ElevatorDisplay;
import gui.ElevatorDisplay.Direction;

import java.util.ArrayList;
import java.util.Collections;

import static java.util.stream.Collectors.joining;

public class Elevator {
    private int id;
    private boolean isDoorOpen;
    private int currentFloor;
    private ArrayList<ElevatorRequest> floorRequests;
    private ArrayList<ElevatorRequest> riderRequests;
    private ArrayList<Person> peopleOnElevator;

    private Direction elevatorDirection;
    private int capacity;
    private int elevatorSpeedInMilliseconds;
    private int doorOpenTimeInMilliseconds;
    private int returnToDefaultFloorTimeout;
    private int timeUntilDoorsClose = 0;
    private int timeLeftOnFloor = 0;
    private int idleCount = 0;

    public Elevator(int id, int capacity, int elevatorSpeedInMilliseconds, int doorOpenTimeInMilliseconds, int returnToDefaultFloorTimeout) throws InvalidValueException {
        setIsDoorOpen(false);
        setCurrentFloor(1);
        setId(id);
        setCapacity(capacity);
        setElevatorDirection(Direction.IDLE);
        setElevatorSpeedInMilliseconds(elevatorSpeedInMilliseconds);
        setDoorOpenTimeInMilliseconds(doorOpenTimeInMilliseconds);
        setReturnToDefaultFloorTimeout(returnToDefaultFloorTimeout);
        floorRequests = new ArrayList<>();
        riderRequests = new ArrayList<>();
        peopleOnElevator = new ArrayList<>();
    }

    public void addFloorRequest(ElevatorRequest elevatorRequest) throws InvalidValueException {
        if (elevatorRequest.getFloorNumber() < 1 || elevatorRequest.getFloorNumber() > Building.getInstance().getNumberOfFloors()) {
            throw new InvalidValueException("Invalid floor request: " + elevatorRequest.getFloorNumber());
        }

        int requestFloorElevator = elevatorRequest.getFloorNumber();
        if (getFloorRequests().contains(elevatorRequest)) {
            return;
        }

        if (getElevatorDirection() == Direction.IDLE) {
            setElevatorDirection(getDirection(requestFloorElevator, getCurrentFloor()));
        }

        floorRequests.add(elevatorRequest);

        String action = "Elevator " + getId() + " is going to floor " + elevatorRequest.getFloorNumber() +
                " for " + elevatorRequest.getDirection() + " request " + getRequestText();
        ElevatorLogger.getInstance().logAction(action);
    }

    public void doTimeSlice(int time) throws InvalidValueException, InvalidStateException {
        if (time < 0) {
            throw new InvalidValueException("Elevator told to move with invalid time: " + time);
        }

        if (getTimeUntilDoorsClose() > 0 || getTimeLeftOnFloor() > 0) {
            int nextDoorCloseTime = Math.max(getTimeUntilDoorsClose() - time, 0);
            setTimeUntilDoorsClose(nextDoorCloseTime);
            int nextTimeLeftOnFloorTime = Math.max(getTimeLeftOnFloor() - time, 0);
            setTimeLeftOnFloor(nextTimeLeftOnFloorTime);
        }

        if (getTimeUntilDoorsClose() > 0 || getTimeLeftOnFloor() > 0) {
            return;
        }

        if (getIsDoorOpen()) {
            closeDoors();
            return;
        }

        if (isRequestPoolEmpty() && getElevatorDirection() == Direction.IDLE && getCurrentFloor() != 1) {
            int idleCount = getIdleCount();
            idleCount += time;
            if (idleCount >= getReturnToDefaultFloorTimeout()) {
                setIdleCount(0);
                addFloorRequest(new ElevatorRequest(Direction.UP, 1));
            }
        }

        setToIdleIfNoMoreRequests();

        if (!isRequestPoolEmpty()) {
            move();
        }
    }

    private void move() throws InvalidStateException, InvalidValueException {
        setDirectionIfTopOrBottomFloor();

        boolean hasFloorRequest = floorHasFloorRequest();
        boolean hasRiderRequest = floorHasRiderRequest();

        if (hasFloorRequest || hasRiderRequest) {
            openDoors();
            setTimeUntilDoorsClose(getDoorOpenTimeInMilliseconds());
            if (hasRiderRequest) {
                movePeopleFromElevatorToFloor();
            }

            if (hasFloorRequest) {
                movePeopleFromFloorToElevator();
            }

        } else {
            if (getIsDoorOpen()) {
                throw new InvalidStateException("Elevator in motion with open doors");
            }

            setTimeLeftOnFloor(getElevatorSpeedInMilliseconds());
            int nextFloor = getElevatorDirection() == Direction.UP ? currentFloor + 1 : currentFloor - 1;
            ElevatorLogger.getInstance()
                    .logAction("Elevator " + getId() + " moving from floor " + currentFloor + " to floor " + nextFloor + " " + getRequestText());
            setCurrentFloor(nextFloor);
        }

        ArrayList<ElevatorRequest> sortedRequests = getSortedRequests();
        if (!sortedRequests.isEmpty()) {
            int nextRequestFloorNumber = sortedRequests.get(0).getFloorNumber();
            if (getElevatorDirection() == Direction.UP && nextRequestFloorNumber < currentFloor) {
                setElevatorDirection(Direction.DOWN);
            } else if (getElevatorDirection() == Direction.DOWN && nextRequestFloorNumber > currentFloor) {
                setElevatorDirection(Direction.UP);
            } else if (nextRequestFloorNumber == getCurrentFloor()) {
                setElevatorDirection(sortedRequests.get(0).getDirection());
            }
        }

        ElevatorDisplay.getInstance().updateElevator(getId(), getCurrentFloor(), getPeopleOnElevator().size(), getElevatorDirection());
    }

    public void pickUpPassenger(Person p) throws InvalidValueException
    {
    	if(p == null) {
    		throw new InvalidValueException("Person is null.");
    	}
        this.peopleOnElevator.add(p);
    }

    private void setToIdleIfNoMoreRequests() {
        if (isRequestPoolEmpty()) {
            ElevatorDisplay.getInstance().updateElevator(getId(), getCurrentFloor(), getPeopleOnElevator().size(), getElevatorDirection());
            setElevatorDirection(Direction.IDLE);
        }
    }

    private void setDirectionIfTopOrBottomFloor() throws InvalidValueException {
        if (getCurrentFloor() == 1 && getElevatorDirection() == Direction.DOWN) {
            setElevatorDirection(Direction.UP);
        }

        if (getCurrentFloor() == Building.getInstance().getNumberOfFloors() && getElevatorDirection() == Direction.UP) {
            setElevatorDirection(Direction.DOWN);
        }
    }

    private boolean isRequestPoolEmpty() {
        return riderRequests.isEmpty() && floorRequests.isEmpty();
    }

    private boolean floorHasRiderRequest() {
        for (ElevatorRequest e : riderRequests) {
            if (e.getFloorNumber() == getCurrentFloor()) {
                return true;
            }
        }
        return false;
    }

    private boolean floorHasFloorRequest() {
        for (ElevatorRequest e : floorRequests) {
            if (e.getDirection() == getElevatorDirection() && e.getFloorNumber() == getCurrentFloor()) {
                return true;
            }
        }

        return false;
    }

    private ArrayList<ElevatorRequest> getSortedRequests() {
        ArrayList<ElevatorRequest> sortedRequests = new ArrayList<>();
        sortedRequests.addAll(floorRequests);
        sortedRequests.addAll(riderRequests);

        sortedRequests.sort((o1, o2) -> o2.getFloorNumber() - o1.getFloorNumber());

        if (getElevatorDirection() == Direction.UP) {
            Collections.reverse(sortedRequests);
        }

        return sortedRequests;
    }

    private void removeFloorRequests(Floor f) {
        floorRequests.removeIf(request -> request.getDirection() == getElevatorDirection() && request.getFloorNumber() == f.getFloorNumber());
    }

    private void movePeopleFromFloorToElevator() throws InvalidValueException {
        ElevatorLogger.getInstance()
                .logAction("Elevator " + getId() + " has arrived at Floor " + currentFloor + " for Floor Request " + getRequestText());

        Floor f = Building.getInstance().getFloor(currentFloor);
        removeFloorRequests(f);

        ArrayList<Person> movedPeople = new ArrayList<>();
        for (int i = 0; i < f.getNumberOfPeopleInLine(); i++) {
            Person p = f.getPersonInLine(i);
            if (p.isTravellingInSameDirection(getElevatorDirection()) && isElevatorOpenCapacity()) {
                movedPeople.add(p);
                peopleOnElevator.add(p);
                ElevatorLogger.getInstance().logAction("Person " + p.getId() + " entered Elevator " + getId() + " " + getRidersText());
                Direction d = getDirection(p.getEndFloor(), p.getStartFloor());
                ElevatorRequest newRequest = new ElevatorRequest(d, p.getEndFloor());
                if (!riderRequests.contains(newRequest)) {
                    riderRequests.add(newRequest);
                }
                ElevatorLogger.getInstance().logAction("Elevator " + getId() +
                        " Rider Request made for Floor " + newRequest.getFloorNumber() + " " + getRequestText());
            }
        }

        for (Person p : movedPeople) {
            f.removeWaitingPerson(p);
        }
    }

    private boolean isElevatorOpenCapacity() { return peopleOnElevator.size() < getCapacity(); }

    private void movePeopleFromElevatorToFloor() throws InvalidValueException {
        ArrayList<ElevatorRequest> filteredRiderRequests = new ArrayList<>();
        for (ElevatorRequest request : riderRequests) {
            if (request.getFloorNumber() == currentFloor) {
                continue;
            }

            filteredRiderRequests.add(request);
        }
        riderRequests = filteredRiderRequests;

        ArrayList<Person> filteredPeople = new ArrayList<>(peopleOnElevator);
        for (Person p : filteredPeople) {
            if (p.isAtDestinationFloor(currentFloor)) {
                peopleOnElevator.remove(p);
                ElevatorLogger.getInstance().logAction("Person " + p.toString() + " has left Elevator " + getId() + " " + getRidersText());
                Building.getInstance().getFloor(currentFloor).addDonePerson(p);
                continue;
            }
        }
    }

    private void openDoors() throws InvalidStateException {
        if (getIsDoorOpen()) {
            throw new InvalidStateException("Opening already open doors");
        }

        ElevatorLogger.getInstance().logAction("Elevator " + getId() + " Doors Open");
        setIsDoorOpen(true);
        ElevatorDisplay.getInstance().openDoors(getId());
    }

    private void closeDoors() throws InvalidStateException {
        if (!getIsDoorOpen()) {
            throw new InvalidStateException("Closing already closed doors");
        }

        ElevatorLogger.getInstance().logAction("Elevator " + getId() + " Doors Close");
        setIsDoorOpen(false);
        ElevatorDisplay.getInstance().closeDoors(getId());
    }

    private String getRequestText() {
        return getRiderRequestsText() + getCurrentFloorRequestsText();
    }

    private String getRiderRequestsText() {
        String riders = riderRequests.stream()
                .map(e -> Integer.toString(e.getFloorNumber()))
                .collect(joining(","));

        riders = riders.equals("") ? "none" : riders;
        return "[Current Rider Requests: " + riders + "]";
    }

    private String getCurrentFloorRequestsText() {
        String floors = floorRequests.stream()
                .map(e -> Integer.toString(e.getFloorNumber()))
                .collect(joining(","));

        floors = floors.equals("") ? "none" : floors;
        return "[Current Floor Requests: " + floors + "]";
    }

    private String getRidersText() {
        String riders = peopleOnElevator.stream()
                .map(Object::toString)
                .collect(joining(","));

        riders = riders.equals("") ? "none" : riders;
        return "[Riders: " + riders + "]";
    }

    private ArrayList<ElevatorRequest> getFloorRequests() {
        return this.floorRequests;
    }

    private void setId(int id) {
        this.id = id;
    }

    private int getId() {
        return id;
    }

    private boolean getIsDoorOpen() {
        return this.isDoorOpen;
    }

    private void setIsDoorOpen(boolean nextValue) {
        this.isDoorOpen = nextValue;
    }

    private int getCurrentFloor() {
        return currentFloor;
    }

    private void setCurrentFloor(int currentFloor) throws InvalidValueException {
        Building.getInstance().validateFloor("Elevator " + getId() + " set to incorrect floor", currentFloor);
        this.currentFloor = currentFloor;
    }

    private Direction getElevatorDirection() {
        return elevatorDirection;
    }

    private void setElevatorDirection(Direction elevatorDirection) {
        this.elevatorDirection = elevatorDirection;
    }

    private int getCapacity() {
        return capacity;
    }

    private void setCapacity(int capacity) throws InvalidValueException {
        if (capacity < 0) {
            throw new InvalidValueException("Elevator capacity must be greater than/equal to 0, got: " + capacity);
        }
        this.capacity = capacity;
    }

    private int getElevatorSpeedInMilliseconds() {
        return elevatorSpeedInMilliseconds;
    }

    private void setElevatorSpeedInMilliseconds(int elevatorSpeedInMilliseconds) throws InvalidValueException {
        if (elevatorSpeedInMilliseconds < 0) {
            throw new InvalidValueException("Elevator speed must be greater than/equal to 0, got: " + elevatorSpeedInMilliseconds);
        }
        this.elevatorSpeedInMilliseconds = elevatorSpeedInMilliseconds;
    }

    private int getDoorOpenTimeInMilliseconds() {
        return doorOpenTimeInMilliseconds;
    }

    private void setDoorOpenTimeInMilliseconds(int doorOpenTimeInMilliseconds) throws InvalidValueException {
        if (doorOpenTimeInMilliseconds < 0) {
            throw new InvalidValueException("Door open time must be greater than/equal to 0, got: " + doorOpenTimeInMilliseconds);
        }
        this.doorOpenTimeInMilliseconds = doorOpenTimeInMilliseconds;
    }

    private int getReturnToDefaultFloorTimeout() {
        return returnToDefaultFloorTimeout;
    }

    private void setReturnToDefaultFloorTimeout(int returnToDefaultFloorTimeout) throws InvalidValueException {
        if (returnToDefaultFloorTimeout < 0) {
            throw new InvalidValueException("Return to first floor time must be greater than/equal to 0, got: " + returnToDefaultFloorTimeout);
        }

        this.returnToDefaultFloorTimeout = returnToDefaultFloorTimeout;
    }

    private int getTimeLeftOnFloor() {
        return timeLeftOnFloor;
    }

    private void setTimeLeftOnFloor(int timeLeftOnFloorIn) throws InvalidValueException {
        if (timeLeftOnFloorIn < 0) {
            throw new InvalidValueException("Time left on floor must be greater than/equal to 0, got: " + timeLeftOnFloorIn);
        }

        this.timeLeftOnFloor = timeLeftOnFloorIn;
    }

    private int getIdleCount() {
        return idleCount;
    }

    private void setIdleCount(int idleCount) throws InvalidValueException {
        if (idleCount < 0) {
            throw new InvalidValueException("Idle count must be greater than/equal to 0, got: " + idleCount);
        }

        this.idleCount = idleCount;
    }

    private int getTimeUntilDoorsClose() {
        return timeUntilDoorsClose;
    }

    private void setTimeUntilDoorsClose(int timeTilClose) throws InvalidValueException {
        if (timeTilClose < 0) {
            throw new InvalidValueException("Time until doors close must be greater than/equal to 0, got: " + timeTilClose);
        }

        this.timeUntilDoorsClose = timeTilClose;
    }

    private static Direction getDirection(int endFloor, int startFloor) {
        return endFloor > startFloor ? Direction.UP : Direction.DOWN;
    }

    private ArrayList<Person> getPeopleOnElevator() {
        return this.peopleOnElevator;
    }

    public void resetState() throws InvalidValueException {
        setIsDoorOpen(false);
        setCurrentFloor(1);
        setElevatorDirection(Direction.IDLE);
        setTimeLeftOnFloor(0);
        setTimeUntilDoorsClose(0);
        setIdleCount(0);

        floorRequests = new ArrayList<>();
        riderRequests = new ArrayList<>();
        peopleOnElevator = new ArrayList<>();
    }

    public String toString() {
        String output = "";

        output += "Elevator " + getId() + " report ...\n";
        output += "Current Direction: " + getElevatorDirection() + "\n";
        output += "Current Floor: " + getCurrentFloor() + "\n";
        output += "Current Floor Requests " + floorRequests + "\n";
        output += "Current Rider Requests " + riderRequests + "\n";
        output += "Current Passengers: " + peopleOnElevator.toString() + "\n";
        output += "Doors Open: " + getIsDoorOpen() + "\n";
        output += "---------------\n";

        return output;
    }
}
