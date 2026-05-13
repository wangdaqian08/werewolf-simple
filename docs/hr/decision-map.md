# Decision Map — Strategic Next Move

## My read

The CFO has escalated. His comments yesterday (salary, "prove your value", contract review, no-laptop rule) are textbook **adverse action territory** under Fair Work Act s.340 — he is altering your position to your prejudice *because* you exercised a workplace right. That is a **stronger** legal position for you than the original incident. Don't waste it by escalating emotionally.

## My read on the leverage

> CFO costs the company **~$250k–$600k** to replace. → **Internal leverage > litigation. Always.**

---

## What to do — flowchart

```mermaid
flowchart TD
    classDef today fill:#fde68a,stroke:#b45309,color:#1a140c,font-weight:bold
    classDef kevin fill:#bfdbfe,stroke:#1d4ed8,color:#0c1a2a,font-weight:bold
    classDef week fill:#bbf7d0,stroke:#15803d,color:#0c1f0c,font-weight:bold
    classDef good fill:#dcfce7,stroke:#15803d,color:#0c1f0c
    classDef warn fill:#fee2e2,stroke:#b91c1c,color:#1a0c0c
    classDef action fill:#f5f0e8,stroke:#8a7a65,color:#1a140c

    T([TODAY — next 6 hours]):::today
    T --> T1[Handwrite notes:<br/>yesterday's CFO meeting<br/>+ today's HR meeting]:::action
    T --> T2[Confirm HR meeting<br/>in writing, within 24h]:::action
    T --> T3[DO NOT contact<br/>the MD yet]:::warn

    T1 --> K
    T2 --> K
    T3 --> K

    K([TOMORROW — Kevin, in person, door shut<br/>“The CFO is questioning your promotion decision<br/>in front of me. I need your advice.”]):::kevin

    K --> K1[Kevin backs you,<br/>will handle it]:::good
    K --> K2[Kevin says<br/>escalate together]:::good
    K --> K3[Kevin distances<br/>himself]:::warn

    K1 --> K1a[Give him 2 weeks.<br/>Keep documenting.]
    K2 --> K2a[JOINT meeting<br/>with MD next week]
    K3 --> K3a[RED FLAG<br/>• Quiet job search now<br/>• Free lawyer consult<br/>• DO NOT resign]:::warn

    K1a --> W
    K2a --> W
    K3a --> W

    W([THIS WEEK — in parallel]):::week
    W --> W1[Fair Work Ombudsman chat<br/>30 min, free<br/>Save transcript]:::action
    W --> W2[Free 30-min employment<br/>lawyer consult — Sydney]:::action
    W --> W3[Update CV +<br/>quiet job search<br/>insurance only]:::action
```

---

## TODAY in detail — the two anchor actions

These two tasks are the ones that quietly build your file. They cost an hour total and determine the strength of every later step.

### 1. Handwritten contemporaneous notes

**Why:** Courts and the Fair Work Commission give significant weight to **contemporaneous notes** — notes written at the time, or as soon as practical afterwards. They are admissible evidence and treated as more reliable than later recollection. Typed notes on a work device are weak (employer-controlled, no clear timestamp). Handwritten notes on paper are strong, and they work around the CFO's no-laptop rule.

**How (do this today, before memory fades):**

1. Take a notebook (or any blank paper).
2. For each meeting (yesterday's CFO meeting, today's HR meeting), write on a fresh page:
   - **Date and time** the meeting started and ended
   - **Location** (room, Teams, phone)
   - **Attendees** by name and role
   - **Exact quotes in quotation marks** for anything significant — e.g. *"you're paid high"*, *"prove your value"*, *"I've reviewed your contracts"*, *"the contract says you have to do overtime"*, *"no laptops in finance meetings"*
   - Your own questions and the responses (or non-responses)
   - Anything physical: who walked out, who raised their voice, who interrupted
3. **Sign and date each page** at the bottom — *"[Your name], [date], [time written]"*.
4. **Photograph each page** with your phone. Upload to **personal cloud** (iCloud / personal Gmail Drive), not OneDrive or anything company-issued.
5. Keep the originals at home, not at the office.

**Rule:** Facts only — what was said, what was done. **No adjectives, no speculation about motive.** *"He stated that…"* not *"He aggressively…"*. The professionalism of the notes is part of what makes them credible later.

### 2. HR follow-up email — same day

**Why:** HR already has their own record of today's meeting — their notes, written in their words, framed their way. Without a written contribution from you, **theirs is the only version on file**, and over time it can be softened, reinterpreted, or used to downplay the facts you raised. A short same-day confirmation email puts **your version** on record in **your words**, locks in HR's stated position (silence means tacit agreement; any correction means you also get their written version), and makes the record contemporaneous — which is what gives it weight later.

**Template — adapt and send today:**

> **To:** [HR partner]
> **Subject:** Confirming our discussion today
>
> Hi [HR partner first name],
>
> Thank you for meeting with me today. To make sure I've understood correctly, I'd like to confirm in writing what we discussed:
>
> 1. I raised the events of 24 April – 8 May and the discussion with the CFO yesterday regarding my salary, my contract, and after-hours contact.
> 2. You advised / your position was that [summarise exactly what HR said — verbatim if possible].
> 3. We agreed the next steps would be [list].
> 4. You confirmed that [any policy clarifications HR gave you].
>
> If any of the above is inaccurate, please let me know in writing so I can correct my record. Otherwise, I'd appreciate written acknowledgement and an indication of expected timing for the next steps.
>
> Kind regards,
> [Your name]

**Don't:**
- Don't editorialise. Don't say *"I was upset"* or *"HR didn't take it seriously"*. Just record.
- Don't escalate in this email (no MD, no legal references). It's a confirmation, not a position.
- Don't BCC anyone. If you want a personal record, forward to a personal email **after** sending — never BCC personal accounts on the original (some companies treat that as data exfiltration).

---

## Fair Work Ombudsman — what it is, and how to use it

**No commitment, no record on your employment file.** Free advice from a government body — also useful as a paper trail you sought professional guidance.

- **Online chat (fastest):** https://www.fairwork.gov.au/ → "Chat with us" (bottom-right). Saves a written transcript.
- **Phone:** 13 13 94 (Mon–Fri).

**Be clear about what FWO does:** advice only. **It does not enforce** Right to Disconnect, adverse action, or psychosocial hazards. The Fair Work Commission is the tribunal that hears those disputes. SafeWork NSW handles the WHS side.

### 30-minute FWO chat — exact script

1. Open the chat. Use your real name once, then anonymous works.
2. Paste this opener:

   > *"I'd like advice on three things: (1) my employer's obligations under the Right to Disconnect, (2) whether discussing my salary and contract in a meeting after I raised workload concerns constitutes adverse action under s.340, and (3) what 'reasonable additional hours' means under s.62 when my contract says overtime is required."*

3. Then ask explicitly:

   > *"What is the time limit for raising a general protections claim if I am dismissed?"*

   (Answer: **21 days**. Memorise this.)

4. If they say *"we can't enforce that, contact FWC"* — correct answer. Note it, move on.
5. Save the transcript / take screenshots. Done.

**The CFO's "contract says you have to do overtime" line is misleading** — the Fair Work Act caps it at *reasonable* additional hours regardless of what the contract says (s.62). Confirm this with FWO so you have it from the source.

### Where to escalate if you ever need to (don't file yet — just know the doors)

- **Fair Work Commission** — actual disputes: Right to Disconnect, general protections, adverse action.
- **SafeWork NSW** — psychosocial hazards. Anonymous tips: https://www.safework.nsw.gov.au/ → *Report an incident or hazard*. **This is the regulator companies actually fear** — improvement/prohibition notices are public and trigger board attention.

---

## My read on the best path — based on similar cases

> **Documented + Kevin onside + either retained with explicit protection, or quiet exit with severance.**
>
> Court is not the path. Median FWC payout is $4–6k. Litigation costs you 1–3 years and a "litigious" reputation.
>
> Your strongest move is making yourself **more expensive for the company to remove than the CFO is to keep**. That happens through Kevin and your paper trail — not through filing anything yet.

---

## Decision triggers — what to do if…

| If… | Then… |
|---|---|
| Kevin backs you and resolves it | Stop. Stay. Keep documenting in case it restarts. |
| Kevin can't move the CFO | Escalate to MD jointly. |
| MD doesn't act within 2 weeks | Free lawyer consult → consider quiet severance negotiation. |
| You're sidelined, demoted, or "performance managed" | This is adverse action. Lawyer up. The 21-day clock starts only at dismissal. |
| You're offered a "package" to leave | Don't sign anything for 7 days. Lawyer reviews first. Typical floor: 3–6 months salary. |
| You're dismissed | File FWC general protections within **21 days**. Non-negotiable. |

---

## The one thing I highly recommend

> **Slow is fast. Quiet is loud.** Every email asking for the company's position in writing, every Kevin conversation, every handwritten note — silently builds your file. You don't need to say anything threatening. The paper trail does the talking later, if it ever needs to.
