package com.werewolf.controller

import com.werewolf.config.RewardProperties
import com.werewolf.dto.RewardRule
import com.werewolf.service.RewardBreakdown
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/rewards")
class RewardController(private val rewardProperties: RewardProperties) {

    /** The scoring table, straight from the rules settlement runs on. */
    @GetMapping("/rules")
    fun rules(): ResponseEntity<List<RewardRule>> = ResponseEntity.ok(RewardBreakdown.catalog(rewardProperties))
}
