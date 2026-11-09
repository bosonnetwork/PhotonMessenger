-- Copyright (c) 2026 -      bosonnetwork.io
-- SPDX-License-Identifier: MIT
-- Photon Room version 2, before delivery outcome storage.

CREATE TABLE contacts_revision (
    id INTEGER NOT NULL,
    revision INTEGER NOT NULL,
    updatedAt INTEGER NOT NULL,
    PRIMARY KEY(id)
);

CREATE TABLE contacts (
    id BLOB NOT NULL,
    type INTEGER NOT NULL,
    sessionKey BLOB,
    name TEXT,
    remark TEXT,
    tags TEXT,
    muted INTEGER NOT NULL,
    blocked INTEGER NOT NULL,
    revision INTEGER NOT NULL,
    createdAt INTEGER NOT NULL,
    updatedAt INTEGER NOT NULL,
    PRIMARY KEY(id)
);

CREATE TABLE channels (
    id BLOB NOT NULL,
    owner BLOB NOT NULL,
    permission INTEGER NOT NULL,
    notice TEXT,
    announce INTEGER NOT NULL,
    PRIMARY KEY(id)
);

CREATE TABLE channel_members (
    id BLOB NOT NULL,
    channelId BLOB NOT NULL,
    role INTEGER NOT NULL,
    joined INTEGER NOT NULL,
    PRIMARY KEY(id, channelId)
);
CREATE INDEX index_channel_members_channelId_joined ON channel_members(channelId, joined);

CREATE TABLE friend_requests (
    id BLOB NOT NULL,
    initiator BLOB NOT NULL,
    hello TEXT,
    createdAt INTEGER NOT NULL,
    updatedAt INTEGER NOT NULL,
    accepted INTEGER NOT NULL,
    acceptedAt INTEGER NOT NULL,
    PRIMARY KEY(id)
);

CREATE TABLE messages (
    rid INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    id BLOB NOT NULL,
    conversationId BLOB NOT NULL,
    version INTEGER NOT NULL,
    recipient BLOB NOT NULL,
    type INTEGER NOT NULL,
    fromId BLOB,
    createdAt INTEGER NOT NULL,
    contentType TEXT,
    contentDisposition TEXT,
    payload BLOB,
    sentAt INTEGER NOT NULL,
    receivedAt INTEGER NOT NULL
);
CREATE UNIQUE INDEX index_messages_id ON messages(id);
CREATE INDEX index_messages_conversationId ON messages(conversationId);

CREATE TABLE channel_invites (
    messageId BLOB NOT NULL,
    channelId BLOB,
    channelName TEXT,
    action INTEGER NOT NULL,
    createdAt INTEGER NOT NULL,
    updatedAt INTEGER NOT NULL,
    PRIMARY KEY(messageId)
);
