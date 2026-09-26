package app.hovanki.e2e.scenarios

import app.hovanki.client.social.UserRelation
import app.hovanki.e2e.scenario
import app.hovanki.e2e.scenario.GameSetups
import app.hovanki.e2e.scenario.GameSetups.PARK
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.ErrorReason
import app.hovanki.shared.protocol.GamePhase
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes

/**
 * Friends, blocks, groups and game invites (docs/adr/0004-accounts-friends-chat.md) through the apps' SocialManager and
 * GameSessionManager. The other side sees a change when its app reloads the list (the friends screen, the inbox poll).
 */
class SocialTest {
    @Test
    fun friendsByNickname() = scenario("Friends by nickname") {
        val anna = player("Anna", at = PARK)
        val bob = player("Bob", at = PARK)
        val carl = player("Carl", at = PARK)
        val annaAccount = anna.signsUp()
        val bobAccount = bob.signsUp()
        val annaId = checkNotNull(anna.userId)
        val bobId = checkNotNull(bob.userId)
        val unconfirmed = newAccount("Carl")
        requireOk(carl.register(unconfirmed), "Carl registers but never confirms the email")

        expectRejected(anna.sendFriendRequest(newAccount("Nobody").nickname), ErrorReason.USER_NOT_FOUND, "nobody")
        expectRejected(
            anna.sendFriendRequest(unconfirmed.nickname),
            ErrorReason.USER_NOT_FOUND,
            "an unconfirmed account",
        )
        expectRejected(anna.sendFriendRequest(annaAccount.nickname), ErrorCode.BAD_REQUEST, "Anna herself")

        requireOk(anna.sendFriendRequest(bobAccount.nickname.uppercase()), "Anna asks Bob (nickname in capitals)")
        check(anna.relationTo(bobId) == UserRelation.OUTGOING, "Anna's request waits")
        requireOk(bob.refreshInbox(), "Bob's start screen polls the inbox")
        check(bob.inbox.friendRequests.map { it.id } == listOf(annaId), "Bob's inbox shows Anna's request")
        requireOk(bob.refreshFriends(), "Bob opens his friends")
        check(bob.relationTo(annaId) == UserRelation.INCOMING, "and so does his friends list")
        requireOk(bob.acceptFriendRequest(annaId), "Bob accepts")
        check(bob.relationTo(annaId) == UserRelation.FRIEND, "Anna is Bob's friend")
        requireOk(anna.refreshFriends(), "Anna opens her friends")
        check(anna.friends?.friends?.map { it.nickname } == listOf(bobAccount.nickname), "Bob is Anna's friend")
        check(anna.friends?.outgoing.orEmpty().isEmpty(), "no request left")

        requireOk(anna.sendFriendRequest(bobId), "asking a friend again")
        check(anna.relationTo(bobId) == UserRelation.FRIEND && anna.friends?.outgoing.orEmpty().isEmpty(), "no change")
        requireOk(bob.removeFriend(annaId), "Bob removes Anna")
        requireOk(anna.refreshFriends(), "Anna reloads")
        check(anna.relationTo(bobId) == UserRelation.NONE && bob.relationTo(annaId) == UserRelation.NONE, "not friends")
    }

    @Test
    fun friendsFromAGame() = scenario("Friends from a game") {
        val sam = player("Sam", at = PARK)
        val anna = player("Anna", at = PARK)
        val vera = player("Vera", at = PARK)
        val boris = player("Boris", at = PARK)
        for (bot in listOf(sam, anna, vera)) bot.signsUp()
        val samId = checkNotNull(sam.userId)
        val annaId = checkNotNull(anna.userId)
        val veraId = checkNotNull(vera.userId)

        sam.createsGame(GameSetups.fast())
        join(anna, vera, boris)
        awaitThat("Sam's lobby shows everybody") { sam.snapshot?.players?.size == 4 }
        val seenBySam = checkNotNull(sam.snapshot).players
        check(seenBySam.single { it.id == boris.id }.userId == null, "Boris is a guest: nobody can add him")
        expectRejected(boris.sendFriendRequest(samId), ErrorReason.ACCOUNT_REQUIRED, "a guest asks Sam")

        // "+ friend" in the lobby: by the account the snapshot shows, the nickname is never typed.
        val samInLobby = checkNotNull(anna.snapshot?.players?.single { it.id == sam.id }?.userId)
        requireOk(anna.sendFriendRequest(samInLobby), "Anna adds Sam from the lobby")
        requireOk(sam.refreshFriends(), "Sam opens his friends")
        requireOk(sam.acceptFriendRequest(annaId), "Sam accepts")
        requireOk(anna.refreshFriends(), "Anna reloads")
        check(anna.relationTo(samId) == UserRelation.FRIEND, "Anna and Sam are friends")

        requireOk(vera.sendFriendRequest(samId), "Vera adds Sam")
        requireOk(sam.sendFriendRequest(veraId), "Sam adds Vera at the same time")
        check(sam.relationTo(veraId) == UserRelation.FRIEND, "asking each other: friends at once")
        requireOk(vera.refreshFriends(), "Vera reloads")
        check(vera.relationTo(samId) == UserRelation.FRIEND, "on both sides")

        requireOk(vera.sendFriendRequest(annaId), "Vera adds Anna")
        requireOk(anna.refreshFriends(), "Anna reloads")
        requireOk(anna.declineFriendRequest(veraId), "Anna declines")
        requireOk(vera.refreshFriends(), "Vera reloads")
        check(vera.relationTo(annaId) == UserRelation.NONE, "the declined request is gone")
        requireOk(anna.sendFriendRequest(veraId), "Anna changes her mind")
        requireOk(anna.declineFriendRequest(veraId), "and withdraws the request")
        requireOk(vera.refreshFriends(), "Vera reloads")
        check(vera.friends?.incoming.orEmpty().isEmpty(), "Vera never sees it")
    }

    @Test
    fun blocking() = scenario("Blocks") {
        val anna = player("Anna", at = PARK)
        val bob = player("Bob", at = PARK)
        val carl = player("Carl", at = PARK)
        val annaAccount = anna.signsUp()
        val bobAccount = bob.signsUp()
        carl.signsUp()
        val annaId = checkNotNull(anna.userId)
        val bobId = checkNotNull(bob.userId)
        anna.befriends(bob)
        anna.befriends(carl)
        val group = anna.createsGroup("Park crew", listOf(bob, carl))

        requireOk(anna.block(bobId), "Anna blocks Bob")
        check(anna.relationTo(bobId) == UserRelation.BLOCKED, "blocked on Anna's phone")
        check(anna.friends?.friends.orEmpty().none { it.id == bobId }, "no longer friends")
        requireOk(anna.refreshGroups(), "Anna opens her groups")
        check(anna.groups.single().members.none { it.id == bobId }, "Bob is out of Anna's group")
        requireOk(bob.refreshFriends(), "Bob opens his friends")
        check(bob.relationTo(annaId) == UserRelation.NONE, "Bob only sees that they are not friends")
        requireOk(bob.refreshGroups(), "Bob opens his groups")
        check(bob.groups.none { it.id == group.id }, "and no longer has the group")

        requireOk(bob.sendFriendRequest(annaAccount.nickname), "Bob asks Anna again")
        check(bob.relationTo(annaId) == UserRelation.OUTGOING, "for Bob it looks sent")
        requireOk(anna.refreshInbox(), "Anna's inbox")
        requireOk(anna.refreshFriends(), "Anna opens her friends")
        check(anna.inbox.friendRequests.isEmpty() && anna.friends?.incoming.orEmpty().isEmpty(), "Anna never sees it")
        expectRejected(anna.sendFriendRequest(bobAccount.nickname), ErrorReason.BLOCKED_BY_YOU, "Anna asks Bob")

        requireOk(anna.unblock(bobId), "Anna unblocks Bob")
        check(anna.relationTo(bobId) == UserRelation.NONE, "no relation left")
        requireOk(bob.refreshFriends(), "Bob reloads")
        check(bob.relationTo(annaId) == UserRelation.NONE, "the request sent during the block is gone")
    }

    @Test
    fun groupsAreHandedOver() = scenario("Groups: create, leave, hand over") {
        val anna = player("Anna", at = PARK)
        val bob = player("Bob", at = PARK)
        val carl = player("Carl", at = PARK)
        val dave = player("Dave", at = PARK)
        for (bot in listOf(anna, bob, carl, dave)) bot.signsUp()
        val annaId = checkNotNull(anna.userId)
        anna.befriends(bob)
        anna.befriends(carl)

        expectRejected(
            anna.createGroup("With a stranger", listOf(checkNotNull(dave.userId))),
            ErrorReason.NOT_FRIENDS,
            "a group with Dave, who is not Anna's friend",
        )
        val group = anna.createsGroup("Park crew", listOf(bob, carl))
        check(group.members.size == 3 && group.members.first().id == annaId, "Anna, Bob and Carl; Anna first")
        requireOk(bob.refreshGroups(), "Bob opens his groups")
        check(bob.groups.single().let { it.id == group.id && it.ownerId == annaId }, "Bob is in Anna's group")
        expectRejected(bob.renameGroup(group.id, "Bob's crew"), ErrorReason.NOT_GROUP_OWNER, "Bob renames it")
        requireOk(anna.renameGroup(group.id, "Fountain crew"), "Anna renames it")

        val successor = group.members.map { it.id }.first { it != annaId }
        requireOk(anna.leaveGroup(group.id), "Anna leaves her own group")
        check(anna.groups.isEmpty(), "Anna has no group")
        requireOk(bob.refreshGroups(), "Bob reloads")
        val handedOver = bob.groups.single()
        check(handedOver.ownerId == successor, "the longest-standing member owns it now")
        check(handedOver.name == "Fountain crew" && handedOver.members.none { it.id == annaId }, "without Anna")

        requireOk(carl.leaveGroup(group.id), "Carl leaves")
        requireOk(bob.leaveGroup(group.id), "Bob leaves")
        requireOk(carl.refreshGroups(), "Carl reloads")
        check(bob.groups.isEmpty() && carl.groups.isEmpty(), "the last one out: the group is gone")
    }

    @Test
    fun deletingTheAccountHandsTheGroupOver() = scenario("Account deletion hands the group over") {
        val anna = player("Anna", at = PARK)
        val bob = player("Bob", at = PARK)
        val carl = player("Carl", at = PARK)
        val annaAccount = anna.signsUp()
        bob.signsUp()
        carl.signsUp()
        val annaId = checkNotNull(anna.userId)
        anna.befriends(bob)
        anna.befriends(carl)
        val group = anna.createsGroup("Park crew", listOf(bob, carl))
        val successor = group.members.map { it.id }.first { it != annaId }

        requireOk(anna.deleteAccount(annaAccount.password), "Anna deletes her account")
        requireOk(carl.refreshGroups(), "Carl opens his groups")
        val handedOver = carl.groups.single()
        check(handedOver.id == group.id && handedOver.ownerId == successor, "the group goes to its next member")
        check(handedOver.members.none { it.id == annaId }, "Anna is gone from it")
        requireOk(bob.refreshFriends(), "Bob opens his friends")
        check(bob.friends?.friends.orEmpty().isEmpty(), "and from Bob's friends")
    }

    @Test
    fun invitesIntoTheLobby() = scenario("Invites into the lobby") {
        val sam = player("Sam", at = PARK)
        val anna = player("Anna", at = PARK)
        val carl = player("Carl", at = PARK)
        val ed = player("Ed", at = PARK)
        val dave = player("Dave", at = PARK)
        val boris = player("Boris", at = PARK)
        val samAccount = sam.signsUp()
        for (bot in listOf(anna, carl, ed, dave)) bot.signsUp()
        for (friend in listOf(anna, carl, ed)) sam.befriends(friend)
        val samId = checkNotNull(sam.userId)
        val invitees = listOf(anna, carl, ed).map { checkNotNull(it.userId) }

        sam.createsGame(GameSetups.fast())
        join(boris)
        expectRejected(
            sam.invite(listOf(checkNotNull(dave.userId))),
            ErrorReason.NOT_FRIENDS,
            "Dave is not Sam's friend",
        )
        expectRejected(boris.invite(listOf(samId)), ErrorReason.ACCOUNT_REQUIRED, "Boris (a guest) invites")
        requireOk(sam.invite(invitees), "Sam invites Anna, Carl and Ed")
        requireOk(dave.refreshInbox(), "Dave's inbox")
        check(dave.inbox.invites.isEmpty(), "Dave got nothing")

        requireOk(anna.refreshInbox(), "Anna's start screen polls the inbox")
        val invite = anna.inbox.invites.single()
        check(
            invite.gameId == gameId && invite.joinCode == joinCode && invite.from.id == samId &&
                invite.from.nickname == samAccount.nickname && invite.groupId == null,
            "Anna's inbox: Sam's invite into his game",
        )
        check(invite.expiresAtMillis - invite.createdAtMillis == 30.minutes.inWholeMilliseconds, "for 30 minutes")
        requireOk(anna.join(invite.joinCode), "Anna accepts: joins with the invite's code")
        check(state().players.single { it.id == anna.id }.userId == anna.userId, "Anna plays with her account")
        requireOk(anna.refreshInbox(), "Anna's inbox")
        check(anna.inbox.invites.isEmpty(), "the invite is answered")

        requireOk(carl.refreshInbox(), "Carl's inbox")
        requireOk(carl.dismissInvite(carl.inbox.invites.single().id), "Carl dismisses the invite")
        check(carl.inbox.invites.isEmpty(), "it is gone")

        requireOk(ed.refreshInbox(), "Ed's inbox")
        check(ed.inbox.invites.size == 1, "Ed has not answered yet")
        sam.startsGame(seekers = listOf(sam))
        awaitPhase(GamePhase.HIDING)
        requireOk(ed.refreshInbox(), "Ed's inbox")
        check(ed.inbox.invites.isEmpty(), "the game started: the invite is gone")
        expectRejected(sam.invite(listOf(invitees.last())), ErrorCode.WRONG_STATE, "an invite after the start")
    }

    @Test
    fun groupInvites() = scenario("Group invites") {
        val anna = player("Anna", at = PARK)
        val sam = player("Sam", at = PARK)
        val bob = player("Bob", at = PARK)
        val carl = player("Carl", at = PARK)
        val dave = player("Dave", at = PARK)
        val bots = listOf(anna, sam, bob, carl, dave)
        for (bot in bots) bot.signsUp()
        val members = listOf(sam, bob, carl, dave)
        for (member in members) anna.befriends(member)
        val group = anna.createsGroup("Crew", members)
        val samId = checkNotNull(sam.userId)
        requireOk(bob.block(samId), "Bob blocks Sam")
        requireOk(sam.block(checkNotNull(dave.userId)), "Sam blocks Dave")
        requireOk(sam.refreshGroups(), "Sam opens his groups")
        check(sam.groups.single().members.size == 5, "a block keeps everyone in Anna's group")

        // Any member plays with the group, not only its owner.
        sam.createsGame(GameSetups.fast())
        join(anna)
        requireOk(sam.invite(groupId = group.id), "Sam invites the whole group")
        for (bot in bots) requireOk(bot.refreshInbox(), "${bot.name}'s inbox")
        val invite = carl.inbox.invites.single()
        check(
            invite.groupId == group.id && invite.groupName == "Crew" && invite.from.id == samId,
            "Carl gets the group invite from Sam",
        )
        check(anna.inbox.invites.isEmpty(), "not Anna: she is in the game already")
        check(sam.inbox.invites.isEmpty(), "not Sam: he invites")
        check(bob.inbox.invites.isEmpty(), "not Bob, who blocked Sam")
        check(dave.inbox.invites.isEmpty(), "not Dave, whom Sam blocked")

        requireOk(carl.join(invite.joinCode), "Carl joins")
        awaitThat("everybody in the lobby sees Carl") {
            listOf(sam, anna).all { bot -> bot.snapshot?.players?.any { it.id == carl.id } == true }
        }
    }
}
