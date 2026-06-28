# Approach Document: PDF Generation Microservice

## 1. Initial Understanding
The primary architectural tension in this problem lies between **high burst-peak resource requirements** and an **extremely tight budget constraint ($150/month)**.

At peak load, the service must handle 1,000 single requests per minute. Given that each PDF render takes up to 3 seconds and consumes 400MB of RAM, a naïve provisioned architecture would require keeping enough concurrent compute capacity alive to process roughly 50 parallel renders simultaneously (50 × 400MB = 20GB of RAM at peak). Maintaining a dedicated cluster with 20GB of RAM 24/7 would easily obliterate the $150 monthly budget.

Additionally, the requirements present data consistency issues due to delays in bulk operations, client network drops during large downloads, and memory exhaustion (OOM errors) when Puppeteer parses massive HTML tables with over 500 lines.

## 2. Assumptions & Clarifying Questions
* **Assumptions:**
    * The core ERP application can extract and serialize all necessary rendering data at the exact moment a PDF generation is requested, sending a "thick" self-contained payload.
    * The cloud provider context is AWS, given the mention of EC2 instances and RDS in the environment.
    * PDF retention guidelines allow for automated lifecycle rules (e.g., moving documents to S3 Glacier after 90 days for compliance).
* **Clarifying Questions:**
    * Do the HTML templates contain heavy external assets (fonts, high-resolution imagery) that require network fetching inside the browser instance, or can assets be pre-baked into the deployment package?
    * What are the exact compliance rules around tamper-evidence? Is an internal cryptographic audit trail using SHA-256 hashes sufficient, or do the documents require visible, officially recognized X.509 digital signatures embedded within the PDF binary itself?

## 3. Capacity Planning & Math
* **Total Volume:** 30,000 PDFs/month ≈ 1,000 PDFs/day average.
* **Storage Footprint:** 30,000 × 500KB (average size) = 15GB/month. Over a year, this accumulates to 180GB, which is highly manageable and cost-effective on standard object storage.
* **Peak Single Concurrency:** Arrival Rate = 1000 requests / 60 seconds ≈ 16.67 requests/sec. Concurrency (Little's Law) = 16.67 req/sec × 3 seconds processing time ≈ 50 concurrent renders.
* **Peak Bulk Concurrency:** 10 bulk requests/min × 100 documents = 1,000 documents/min. This effectively doubles our peak arrival rate during combined spikes, driving required short-term concurrency up to ≈ 100 parallel browser instances.
* **Network Bandwidth (Outbound):** 30,000 documents × 1MB (max size) = 30GB data transfer out per month.

## 4. Design Decisions

### Decision 1: Decoupling Compute via Serverless Scale-to-Zero Renderers
* **Alternatives considered:** Fixed EC2 Auto-Scaling Group running Dockerized Node.js workers vs. AWS ECS Fargate Tasks with fine-grained scaling policies.
* **Chosen approach:** Hybrid Architecture consisting of an **ECS Fargate Spring Boot Orchestrator** combined with an **AWS Lambda Compute Pool** running a compressed layer of Headless Chrome/Puppeteer.
* **Why:** Spring Boot is exceptional at handling I/O coordination, web requests, queue management, and data transformations efficiently within a tiny memory footprint (e.g., 512MB–1GB RAM instance). It offloads the volatile, high-RAM rendering work (400MB per execution) to AWS Lambda. Lambda scales instantly from 0 to 100+ concurrent allocations during the peak minute and scales back down to zero immediately after, bypassing the need to pay for idle RAM.
* **Tradeoff accepted:** Cold starts on AWS Lambda when a peak period begins can add 1–2 seconds to the initial execution, but this remains well within the 5-second single download SLA.

### Decision 2: Handling OOM Errors on Massive 500+ Row Tables
* **Alternatives considered:** Vertically scaling worker memory dynamically vs. Client-side pagination.
* **Chosen approach:** Programmatic Chunking and Native PDF Merging.
* **Why:** When the Spring Boot Orchestrator receives a JSON payload containing an array of line items exceeding a threshold (e.g., 100 items), it splits the single request into multiple independent page payloads. It triggers parallel Lambda executions to generate partial PDFs for pages 1–10, 11–20, etc. Once all fragments are safely rendered without crashing Chrome's memory space, the Spring Boot orchestrator streams them into memory using Apache PDFBox, merges them into a single continuous document, and closes the stream.
* **Tradeoff accepted:** Higher orchestration complexity and increased execution count on Lambda, but it guarantees deterministic memory boundaries.

### Decision 3: Solving Unreliable Client Connections and Bulk Progress Tracking
* **Alternatives considered:** Streaming files directly over HTTP response streams vs. WebSockets.
* **Chosen approach:** Reactive Redis State Tracking + S3 Resumable Pre-signed URLs.
* **Why:** Streaming files directly over an HTTP response from a server means if a small business client drops connection at megabyte 15 of a 50MB bulk ZIP file, the entire server process fails, wasting resources. Instead, individual bulk files are written straight to Amazon S3. The Spring Boot orchestrator uses Redis hashes to update real-time progress percentages (e.g., `35/100 completed`). When complete, it generates an S3 Pre-signed URL. S3 natively supports standard HTTP Range Requests (`Accept-Ranges: bytes`), allowing client browsers or download managers to automatically resume interrupted file transfers right where they broke off.
* **Tradeoff accepted:** Storage writes are required for every operation, necessitating an automated lifecycle policy to purge non-archived temporary ZIP files after 24 hours.

### Decision 4: Data Consistency across Bulk Operations
* **Alternatives considered:** Locking database tables during generation vs. Database Read Isolation Levels (Repeatable Read).
* **Chosen approach:** Thick Immutable Payload Push.
* **Why:** Passing entity IDs (like `invoice_id`) to a background worker leads to race conditions if an entity is modified midway through a 3-minute batch run. The Core App serializes the complete point-in-time snapshot data of all 100 documents into a single JSON array at the exact millisecond the user clicks "Generate". The microservice functions completely side-effect-free based entirely on the pushed data snapshot, eliminating any mid-process database state drift.
* **Tradeoff accepted:** Higher network payload size transit between the core application and the PDF microservice.

### Decision 5: Non-Intrusive Tamper Evidence
* **Alternatives considered:** Storing file hashes on a private distributed ledger vs. Cryptographic PDF Signatures.
* **Chosen approach:** Embedded Cryptographic Document Signing via Apache PDFBox.
* **Why:** When the Spring Boot service finishes merging or generating the final PDF, it passes the byte array through an internal signing module. Using a private key safely loaded from AWS Secrets Manager, it generates a standard invisible digital signature and embeds it into the PDF's native metadata structure. If a user tries to alter figures or line items inside the PDF later using an editor, the cryptographic checksum fails, and software like Adobe Acrobat immediately displays a prominent warning stating the document has been modified after creation.
* **Tradeoff accepted:** Slight CPU overhead in the Spring Boot microservice to calculate cryptographic signatures on large documents.

## 5. Weaknesses & Future Improvements
* **Weakest Component:** The dependency on browser-based rendering (Puppeteer). Running a full headless web browser to convert HTML to text layouts is fundamentally heavy.
* **10x Scale Evolution:** If the system needs to scale to 300,000 documents a month, I would advocate for eliminating Puppeteer entirely. I would migrate the system templates over to a native compiling layout engine like **JasperReports** or a compiled Rust alternative like **typst**. These engines generate structured PDFs programmatically in milliseconds using a fraction of the CPU and memory resources required by Chromium.

## 6. One Thing the Problem Statement Didn't Mention
* **Font Provisioning and Internationalization (i18n):** When running headless Chromium within stripped-down Linux micro-containers or Lambda environments, the operating system lacks default commercial fonts (like Arial, Helvetica) and regional font families (such as Devanagari or regional scripts for GST invoicing). Without explicit configuration, rendering engines will substitute missing text characters with blank rectangles or unreadable characters ("tofu"). The deployment bundle must explicitly package and load custom TrueType fonts (`.ttf`) directly into the rendering lifecycle.

## 7. Cost Estimation (Line-by-Line Breakdown)
* **Compute (Orchestrator):** AWS ECS Fargate – 1 Instance running continuously (0.25 vCPU, 512MB RAM) ≈ **$9.00/mo**.
* **Compute (Render Pool):** AWS Lambda – 30,000 executions/month × 3 seconds average = 90,000 GB-seconds. This falls completely inside the AWS Lambda Perpetual Free Tier (400,000 GB-seconds) = **$0.00/mo**.
* **Storage (Amazon S3):** 15GB active storage + API request costs = **$0.50/mo**.
* **Caching & Queueing:** Amazon ElastiCache Redis (t4g.micro cache instance for tracking job status) ≈ **$12.00/mo**.
* **Data Transfer (Outbound Internet Bandwidth):** 30GB egress out of AWS = **$2.70/mo**.
* **Total Estimated System Cost:** **~$24.20 / month** (Roughly ₹2,000 INR, well below the maximum budget limit of ₹12,500).