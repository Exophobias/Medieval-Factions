package com.dansplugins.factionsystem.exception

/**
 * Thrown when a faction's head has departed, nobody remains to inherit, and
 * factions.allowLeaderlessFactions forbids a faction without one.
 *
 * Refusing the save is the only honest answer here: the alternative is writing a state the server
 * operator has explicitly disallowed. Reaching it through MF's own commands should be impossible,
 * since the last member leaving disbands the faction outright when leaderless factions are off, so in
 * practice this guards direct use of the faction service.
 */
class NoSuccessorException(message: String) : RuntimeException(message)
