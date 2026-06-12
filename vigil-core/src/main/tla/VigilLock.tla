---------------------------- MODULE VigilLock ----------------------------
EXTENDS Naturals

CONSTANTS Pods, MaxToken

VARIABLES token, holder, podState, podToken, runStatus

vars == <<token, holder, podState, podToken, runStatus>>

TypeOK ==
  /\ token \in 0..MaxToken
  /\ holder \in Pods \cup {"NONE"}
  /\ podState \in [Pods -> {"IDLE", "RUNNING", "ZOMBIE", "DONE"}]
  /\ podToken \in [Pods -> 0..MaxToken]
  /\ runStatus \in [Pods -> {"NONE", "RUNNING", "SUCCESS", "FAILED", "STOLEN"}]

Init ==
  /\ token = 0
  /\ holder = "NONE"
  /\ podState = [p \in Pods |-> "IDLE"]
  /\ podToken = [p \in Pods |-> 0]
  /\ runStatus = [p \in Pods |-> "NONE"]

Acquire(p) ==
  /\ holder = "NONE"
  /\ podState[p] = "IDLE"
  /\ token < MaxToken
  /\ token' = token + 1
  /\ holder' = p
  /\ podToken' = [podToken EXCEPT ![p] = token + 1]
  /\ podState' = [podState EXCEPT ![p] = "RUNNING"]
  /\ runStatus' = [runStatus EXCEPT ![p] = "RUNNING"]

Renew(p) ==
  /\ podState[p] = "RUNNING"
  /\ podToken[p] = token
  /\ UNCHANGED vars

Release(p) ==
  /\ podState[p] = "RUNNING"
  /\ podToken[p] = token
  /\ holder' = "NONE"
  /\ podState' = [podState EXCEPT ![p] = "DONE"]
  /\ runStatus' = [runStatus EXCEPT ![p] = "SUCCESS"]
  /\ UNCHANGED <<token, podToken>>

GCPause(p) ==
  /\ podState[p] = "RUNNING"
  /\ token < MaxToken
  /\ token' = token + 1
  /\ holder' = "NONE"
  /\ podState' = [podState EXCEPT ![p] = "ZOMBIE"]
  /\ runStatus' = [runStatus EXCEPT ![p] = "STOLEN"]
  /\ UNCHANGED podToken

StolenWrite(p) ==
  /\ podState[p] = "ZOMBIE"
  /\ podToken[p] < token
  /\ UNCHANGED vars

Recover ==
  \E q \in Pods :
    /\ podState[q] = "IDLE"
    /\ holder = "NONE"
    /\ \E p \in Pods : podState[p] = "ZOMBIE"
    /\ token < MaxToken
    /\ token' = token + 1
    /\ holder' = q
    /\ podToken' = [podToken EXCEPT ![q] = token + 1]
    /\ podState' = [podState EXCEPT ![q] = "RUNNING"]
    /\ runStatus' = [runStatus EXCEPT ![q] = "RUNNING"]

Next ==
  \/ \E p \in Pods : Acquire(p)
  \/ \E p \in Pods : Renew(p)
  \/ \E p \in Pods : Release(p)
  \/ \E p \in Pods : GCPause(p)
  \/ \E p \in Pods : StolenWrite(p)
  \/ Recover

Spec == Init /\ [][Next]_vars /\ WF_vars(Next)

MutualExclusion ==
  \A p1, p2 \in Pods :
    (podState[p1] = "RUNNING" /\ podState[p2] = "RUNNING") => p1 = p2

NoZombieWrite ==
  \A p \in Pods : podState[p] = "ZOMBIE" => podToken[p] < token

TokenMonotonicity == [][token' >= token]_token

EventualProgress == <>(holder /= "NONE")

StolenRunMarked ==
  \A p \in Pods : podState[p] = "ZOMBIE" => runStatus[p] = "STOLEN"

==========================================================================
