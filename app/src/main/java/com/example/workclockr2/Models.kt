package com.example.workclockr2

import java.util.UUID

data class R2Config(
    val accountId: String,
    val bucket: String,
    val accessKey: String,
    val secretKey: String,
    val allowedSsid: String
)

data class Punch(
    val id: String = UUID.randomUUID().toString(),
    val employeeId: String,
    val at: String,
    val type: String,
    val day: String
)

data class MakeUpSegment(
    val start: String = "09:00",
    val end: String = "18:00"
)

data class Employee(
    val employeeId: String,
    val displayName: String,
    val role: String,
    val passwordSalt: String,
    val passwordHash: String,
    val mustChangePassword: Boolean,
    val enabled: Boolean
)

data class ProfileData(
    val employeeId: String,
    val name: String,
    val gender: String,
    val age: String,
    val avatarKey: String,
    val background: Int,
    val phone: String = "",
    val wechat: String = "",
    val qq: String = "",
    val email: String = "",
    val address: String = "",
    val emergencyContact: String = "",
    val emergencyPhone: String = "",
    val occupation: String = "",
    val bio: String = ""
)
