package edu.purdue.dblab.matrix

import org.apache.spark._
import org.apache.spark.rdd.RDD

import scala.collection.concurrent.TrieMap
//import java.util.concurrent.ConcurrentHashMap
//import scala.collection.JavaConversions.asScalaIterator

import breeze.linalg.{Matrix => BM}

import scala.collection.mutable
import scala.collection.mutable.{ArrayBuffer, Map => MMap}

/**
 * Created by yongyangyu on 7/15/15.
 */
class BlockPartitionMatrix (
    var blocks: RDD[((Int, Int), MLMatrix)],
    val ROWS_PER_BLK: Int = 1,
    val COLS_PER_BLK: Int = 1,
    private var nrows: Long = 0,
    private var ncols: Long = 0) extends Matrix with Logging {

    val ROW_BLK_NUM = math.ceil(nRows() * 1.0 / ROWS_PER_BLK).toInt
    val COL_BLK_NUM = math.ceil(nCols() * 1.0 / COLS_PER_BLK).toInt

    private var groupByCached: RDD[(Int, Iterable[(Int, MLMatrix)])] = null

    val numPartitions: Int = 64 // 8 workers

    var sparsity: Double = 0.0

    // these two maps used for sampling current distributed matrix for
    // estimating matrix product nonzero element numbers
    val rowBlkMap = scala.collection.mutable.Map[Int, Int]()
    val colBlkMap = scala.collection.mutable.Map[Int, Int]()

    override def nRows(): Long = {
        if (nrows <= 0L && blocks != null) {
            getDimension()
        }
        nrows
    }

    override def nCols(): Long = {
        if (ncols <= 0L && blocks != null) {
            getDimension()
        }
        ncols
    }

    override def nnz(): Long = {
        //println("computing nnz ...")
        val cnt = blocks.map{ case ((i, j), mat) =>
            mat match {
              case den: DenseMatrix => den.values.length.toLong
              case sp: SparseMatrix => sp.values.length.toLong
              case _ => 0L
            }
        }.reduce(_ + _)
        //println("finish nnz computing ...")
        cnt
    }

    // sparsity is defined as nnz / (m * n) where m and n are number of rows and cols
    // Is this a fast operation? Does there exist any better way to get the precise
    // sparsity info of the underlying distributed matrix.
    def getSparsity(): Double = {
        if (sparsity <= 0) {
            val nnz = blocks.map { case ((rid, cid), mat) =>
                mat match {
                    case den: DenseMatrix => den.values.length
                    case sp: SparseMatrix => sp.values.length
                }
            }.reduce(_ + _)
            sparsity = nnz * 1.0 / (nRows() * nCols())
        }
        sparsity
    }

    def stat() = {
        println("-" * 40 )
        println(s"Block partition matrix has $ROW_BLK_NUM row blks")
        println(s"Block partition matrix has $COL_BLK_NUM col blks")
        println(s"Each block size is $ROWS_PER_BLK by $COLS_PER_BLK")
        println(s"Matrix has ${nRows()} rows")
        println(s"Matrix has ${nCols()} cols")
        println(s"block RDD has ${blocks.count()} nonzero blocks")
        println("-" * 40 )
    }

    def saveAsTextFile(path: String) = {
        blocks.saveAsTextFile(path)
    }

    def repartition(count: Int) = {
        this.blocks = blocks.repartition(count)
    }

    def partitioner = blocks.partitioner.get

    private type MatrixBlk = ((Int, Int), MLMatrix)
    private type PartitionScheme = (Int, Int)

    // generate a block cyclic partitioner to partition the whole block matrix into `numPartitions` partitions
    // watch out the special case when the matrix only has a single row/col block id
    private def genBlockPartitioner(): BlockCyclicPartitioner = {
        val scale = 1.0 / math.sqrt(numPartitions)
        //println(s"In genBlockPartitioner: ROW_BLKS = $ROW_BLK_NUM")
        //println(s"In genBlockPartitioner: COL_BLKS = $COL_BLK_NUM")
        var ROW_BLKS_PER_PARTITION = math.round(math.max(scale * ROW_BLK_NUM, 1.0)).toInt
        var COL_BLKS_PER_PARTITION = math.round(math.max(scale * COL_BLK_NUM, 1.0)).toInt
        if (ROW_BLKS_PER_PARTITION == 1 || COL_BLKS_PER_PARTITION == 1) {
            if (ROW_BLKS_PER_PARTITION != 1) {
                ROW_BLKS_PER_PARTITION = math.round(math.max(ROW_BLKS_PER_PARTITION / 8.0, 1.0)).toInt
            }
            if (COL_BLKS_PER_PARTITION != 1) {
                COL_BLKS_PER_PARTITION = math.round(math.max(COL_BLKS_PER_PARTITION / 8.0, 1.0)).toInt
            }
        }
        new BlockCyclicPartitioner(ROW_BLK_NUM, COL_BLK_NUM, ROW_BLKS_PER_PARTITION, COL_BLKS_PER_PARTITION)
    }

    private lazy val blkInfo = blocks.mapValues(block => (block.numRows, block.numCols)).cache()

    private def getDimension(): Unit = {
        val (rows, cols) = blkInfo.map { x =>
            val blkRowIdx = x._1._1
            val blkColIdx = x._1._1
            val rowSize = x._2._1
            val colSize = x._2._2
            (blkRowIdx.toLong * ROWS_PER_BLK + rowSize, blkRowIdx.toLong * COLS_PER_BLK + colSize)
        }.reduce { (x, y) =>
          (math.max(x._1, y._1), math.max(x._2, y._2))
        }
        if (nrows <= 0) nrows = rows
        assert(rows <= nrows, s"Number of rows $rows is more than claimed $nrows")
        if (ncols <= 0) ncols = cols
        assert(cols <= ncols, s"Number of cols $cols is more than claimed $ncols")
    }

    /*
     * Caches the underlying RDD
     */
    def cache(): this.type = {
        blocks.cache()
        this
    }

    def partitionBy(p: Partitioner): this.type = {
        blocks = blocks.partitionBy(p)
        this
    }

    def partitionByBlockCyclic(): this.type = {
        blocks = blocks.partitionBy(genBlockPartitioner())
        this
    }

    /*
     * Validates the block matrix to find out any errors or exceptions.
     */
    def validate(): Unit = {
        logDebug("Validating block partition matrices ...")
        getDimension()
        logDebug("Block partition matrices dimensions are OK ...")
        // check if there exists duplicates for the keys, i.e., duplicate indices
        blkInfo.countByKey().foreach{ case (key, count) =>
            if (count > 1) {
                throw new SparkException(s"Found duplicate indices for the same block, key is $key.")
            }
        }
        logDebug("Block partition matrices indices are OK ...")
        val dimMsg = s"Dimensions different than ROWS_PER_BLK: $ROWS_PER_BLK, and " +
                    s"COLS_PER_BLK: $COLS_PER_BLK. Blocks on the right and bottom edges may have " +
                    s"smaller dimensions. The problem may be fixed by repartitioning the matrix."
        // check for size of each individual block
        blkInfo.foreach{ case ((blkRowIdx, blkColInx), (m, n)) =>
            if ((blkRowIdx < ROW_BLK_NUM - 1 && m != ROWS_PER_BLK) ||
                (blkRowIdx == ROW_BLK_NUM - 1 && (m <= 0 || m > ROWS_PER_BLK))) {
                throw new SparkException(s"Matrix block at ($blkRowIdx, $blkColInx) has " + dimMsg)
            }
            if ((blkColInx < COL_BLK_NUM - 1 && n != COLS_PER_BLK) ||
                (blkColInx == COL_BLK_NUM - 1 && (n <= 0 || n > COLS_PER_BLK))) {
                throw new SparkException(s"Matrix block at ($blkRowIdx, $blkColInx) has " + dimMsg)
            }
        }
        logDebug("Block partition matrix dimensions are OK ...")
        logDebug("Block partition matrix is valid.")
    }

    def transpose(): BlockPartitionMatrix = {
        val transposeBlks = blocks.map {
            case ((blkRowIdx, blkColIdx), mat) => ((blkColIdx, blkRowIdx), mat.transpose)
        }
        new BlockPartitionMatrix(transposeBlks, COLS_PER_BLK, ROWS_PER_BLK, nCols(), nRows())
    }

    def t: BlockPartitionMatrix = transpose()

    /*
     * Collect the block partitioned matrix on the driver side for debugging purpose only.
     */
    def toLocalMatrix(): MLMatrix = {
        require(nRows() < Int.MaxValue, s"Number of rows should be smaller than Int.MaxValue, but " +
        s"found ${nRows()}")
        require(nCols() < Int.MaxValue, s"Number of cols should be smaller than Int.MaxValue, but " +
        s"found ${nCols()}")
        require(nnz() < Int.MaxValue, s"Total number of the entries should be smaller than Int.MaxValue, but " +
        s"found ${nnz()}")
        val m = nRows().toInt
        val n = nCols().toInt
        val memSize = m * n / 131072  // m-by-n * 8 byte / (1024 * 1024) MB
        if (memSize > 500) logWarning(s"Storing local matrix requires $memSize MB")
        val localBlks = blocks.collect()
        val values = Array.fill(m * n)(0.0)
        localBlks.foreach{
            case ((blkRowIdx, blkColIdx), mat) =>
                val rowOffset = blkRowIdx * ROWS_PER_BLK
                val colOffset = blkColIdx * COLS_PER_BLK
                // (i, j) --> (i + rowOffset, j + colOffset)
                for (i <- 0 until mat.numRows; j <- 0 until mat.numCols) {
                    val indexOffset = (j + colOffset) * m + (rowOffset + i)
                    values(indexOffset) = mat(i, j)
                }
        }
        new DenseMatrix(m, n, values)
    }

    def *(alpha: Double): BlockPartitionMatrix = {
        multiplyScalar(alpha)
    }

    def *(other: BlockPartitionMatrix, partitioner: Partitioner): BlockPartitionMatrix = {
        require(nRows() == other.nRows(), s"Two matrices must have the same number of rows. " +
          s"A.rows: ${nRows()}, B.rows: ${other.nRows()}")
        require(nCols() == other.nCols(), s"Two matrices must have the same number of cols. " +
          s"A.cols: ${nCols()}, B.cols: ${other.nCols()}")
        var rdd1 = blocks
        if (!rdd1.partitioner.get.isInstanceOf[partitioner.type]) {
            rdd1 = rdd1.partitionBy(partitioner)
        }
        var rdd2 = other.blocks
        if (!rdd2.partitioner.get.isInstanceOf[partitioner.type]) {
            rdd2 = rdd2.partitionBy(partitioner)
        }
        val rdd = rdd1.zipPartitions(rdd2, preservesPartitioning = true) {
            case (iter1, iter2) =>
                val idx2val = new TrieMap[(Int, Int), MLMatrix]()
                val res = new TrieMap[(Int, Int), MLMatrix]()
                for (elem <- iter1) {
                    val key = elem._1
                    if (!idx2val.contains(key)) idx2val.putIfAbsent(key, elem._2)
                }
                for (elem <- iter2) {
                    val key = elem._1
                    if (idx2val.contains(key)) {
                        val tmp = idx2val.get(key).get
                        res.putIfAbsent(key, LocalMatrix.elementWiseMultiply(tmp, elem._2))
                    }
                }
            res.iterator
        }
        new BlockPartitionMatrix(rdd, ROWS_PER_BLK, COLS_PER_BLK, nRows(), nCols())
    }

    def *:(alpha: Double): BlockPartitionMatrix = {
        multiplyScalar(alpha)
    }

    def /(alpha: Double): BlockPartitionMatrix = {
        require(alpha != 0, "Block matrix divided by 0 error!")
        multiplyScalar(1.0 / alpha)
    }

    // This method may need some caution if the matrix is sparse.
    // What should be the correct semantics for divided by 0 entries?
    def /:(alpha: Double): BlockPartitionMatrix = {
        require(alpha != 0, "Block matrix divided by 0 error!")
        multiplyScalar(1.0 / alpha)
    }

    def divideVector(vec: BlockPartitionMatrix): BlockPartitionMatrix = {
        require(nCols() == vec.nRows(), s"Dimension incompatible matrix.ncols=${nCols()}, " +
          s"vec.nrows=${vec.nRows()}")
        // duplicate vec to each partition of current matrix, instead of joining two RDDs
        val ndup = blocks.partitions.length
        val dupRDD = duplicateCrossPartitions(vec.blocks, ndup)
        val RDD = dupRDD.zipPartitions(blocks, preservesPartitioning = true) { (iter1, iter2) =>
            val dup = iter1.next()._2
            for {
                x <- iter2
                div <- dup
                if (x._1._2 == div._1._1)
            } yield (x._1, LocalMatrix.matrixDivideVector(x._2, div._2))
        }
        //println(RDD.map{ case ((i, j), v) => (i, j)}.collect().mkString("[", ",", "]"))
        /*val RDD1 = blocks.map { case ((i, j), mat) =>
            (j, ((i,j), mat))
        }
        val RDD2 = vec.blocks.map { case ((i, j), mat) =>
            (i, mat)
        }
        val RDD = RDD1.join(RDD2).map { case (idx, (((i, j), mat1), mat2)) =>
            ((i, j), LocalMatrix.matrixDivideVector(mat1, mat2))
        }*/
        new BlockPartitionMatrix(RDD, ROWS_PER_BLK, COLS_PER_BLK, nRows(), nCols())
    }

    def /(other: BlockPartitionMatrix, partitioner: Partitioner): BlockPartitionMatrix = {
        require(nRows() == other.nRows(), s"Two matrices must have the same number of rows. " +
          s"A.rows: ${nRows()}, B.rows: ${other.nRows()}")
        require(nCols() == other.nCols(), s"Two matrices must have the same number of cols. " +
          s"A.cols: ${nCols()}, B.cols: ${other.nCols()}")
        var rdd1 = blocks
        if (!rdd1.partitioner.isDefined || !rdd1.partitioner.get.isInstanceOf[partitioner.type]) {
            rdd1 = rdd1.partitionBy(partitioner)
        }
        var rdd2 = other.blocks
        if (!rdd2.partitioner.isDefined || !rdd2.partitioner.get.isInstanceOf[partitioner.type]) {
            rdd2 = rdd2.partitionBy(partitioner)
        }
        val rdd = rdd1.zipPartitions(rdd2, preservesPartitioning = true) {
            case (iter1, iter2) =>
                val idx2val = new TrieMap[(Int, Int), MLMatrix]()
                val res = new TrieMap[(Int, Int), MLMatrix]()
                for (elem <- iter1) {
                    val key = elem._1
                    if (!idx2val.contains(key)) idx2val.putIfAbsent(key, elem._2)
                }
                for (elem <- iter2) {
                    val key = elem._1
                    if (idx2val.contains(key)) {
                        val tmp = idx2val.get(key).get
                        res.putIfAbsent(key, LocalMatrix.elementWiseDivide(tmp, elem._2))
                    }
                }
                res.iterator
        }
        new BlockPartitionMatrix(rdd, ROWS_PER_BLK, COLS_PER_BLK, nRows(), nCols())
    }

    def multiplyScalar(alpha: Double): BlockPartitionMatrix = {
        /*println(blocks.partitions.length + " partitions in blocks RDD" +
          s" with ${nRows()} rows ${nCols()} cols")
        blocks.mapPartitionsWithIndex{ case (id, iter) =>
            var count = 0
            for (tuple <- iter) {
                tuple._2 match {
                    case dm: DenseMatrix => count += dm.values.length
                    case sp: SparseMatrix => count += sp.values.length
                    case _ => throw new SparkException("Format wrong")
                }
            }
            Iterator((id, count))
        }.collect().foreach(println)*/

        val rdd = blocks.mapValues(mat => LocalMatrix.multiplyScalar(alpha, mat))
        new BlockPartitionMatrix(rdd, ROWS_PER_BLK, COLS_PER_BLK, nRows(), nCols())
    }

    def multiplyScalarInPlace(alpha: Double): BlockPartitionMatrix = {
        blocks.foreach { case ((rowIdx, colIdx), mat) =>
            mat.update(x => alpha * x)
        }
        this
    }

    // add a scalar to existing entries, do not touch 0 entries
    def +(alpha: Double): BlockPartitionMatrix = {
        val RDD = blocks.map { case (idx, mat) =>
            (idx, LocalMatrix.addScalar(mat, alpha))
        }
        new BlockPartitionMatrix(RDD, ROWS_PER_BLK, COLS_PER_BLK, nRows(), nCols())
    }

    def -(alpha: Double): BlockPartitionMatrix = {
        this.+(-alpha)
    }

    def +(other: BlockPartitionMatrix): BlockPartitionMatrix = {
        if (blocks != null && other.blocks != null) {
            if (blocks.partitioner != None) {
                add(other, (ROWS_PER_BLK, COLS_PER_BLK), blocks.partitioner.get)
            }
            else if (other.blocks.partitioner != None) {
                add(other, (ROWS_PER_BLK, COLS_PER_BLK), other.blocks.partitioner.get)
            }
            else {
                add(other, (ROWS_PER_BLK, COLS_PER_BLK), genBlockPartitioner())
            }
        }
        else {
            if (blocks == null) other
            else this
        }
    }

    def *(other: BlockPartitionMatrix): BlockPartitionMatrix = {
        if (blocks != null && other.blocks != null) {
            if (blocks.partitioner.get != null) {
                this.*(other, blocks.partitioner.get)
            }
            else if (other.blocks.partitioner.get != null) {
                this.*(other, other.blocks.partitioner.get)
            }
            else {
                this.*(other, genBlockPartitioner())
            }
        }
        else {
            if (blocks == null) this
            else other
        }
    }

    def /(other: BlockPartitionMatrix): BlockPartitionMatrix = {
        if (blocks != null && other.blocks != null) {
            if (blocks.partitioner.get != null) {
                this./(other, blocks.partitioner.get)
            }
            else if (other.blocks.partitioner.get != null) {
                this./(other, other.blocks.partitioner.get)
            }
            else {
                this./(other, genBlockPartitioner())
            }
        }
        else {
            BlockPartitionMatrix.zeros()
        }
    }

    def +(other: BlockPartitionMatrix,
          dimension: PartitionScheme = (ROWS_PER_BLK, COLS_PER_BLK),
          partitioner: Partitioner): BlockPartitionMatrix = {
        add(other, dimension, partitioner)
    }

    /*
     * Adds two block partitioned matrices together. The matrices must have the same size but may have
     * different partitioning schemes, i.e., different `ROWS_PER_BLK` and `COLS_PER_BLK` values.
     * @param dimension, specifies the (ROWS_PER_PARTITION, COLS_PER_PARTITION) of the result
     */
    def add(other: BlockPartitionMatrix,
            dimension: PartitionScheme = (ROWS_PER_BLK, COLS_PER_BLK),
            partitioner: Partitioner): BlockPartitionMatrix = {
        //val t1 = System.currentTimeMillis()
        require(nRows() == other.nRows(), s"Two matrices must have the same number of rows. " +
        s"A.rows: ${nRows()}, B.rows: ${other.nRows()}")
        require(nCols() == other.nCols(), s"Two matrices must have the same number of cols. " +
        s"A.cols: ${nCols()}, B.cols: ${other.nCols()}")
        // simply case when both matrices are partitioned in the same fashion
        if (ROWS_PER_BLK == other.ROWS_PER_BLK && COLS_PER_BLK == other.COLS_PER_BLK &&
            ROWS_PER_BLK == dimension._1 && COLS_PER_BLK == dimension._2) {
            addSameDim(blocks.partitionBy(partitioner), other.blocks.partitionBy(partitioner),
                ROWS_PER_BLK, COLS_PER_BLK)
        }
        // need to repartition the matrices according to the specification
        else {
            var (repartA, repartB) = (true, true)
            if (ROWS_PER_BLK == dimension._1 && COLS_PER_BLK == dimension._2) {
                repartA = false
            }
            if (other.ROWS_PER_BLK == dimension._1 && other.COLS_PER_BLK == dimension._2) {
                repartB = false
            }
            var (rddA, rddB) = (blocks, other.blocks)
            if (repartA) {
                rddA = getNewBlocks(blocks, ROWS_PER_BLK, COLS_PER_BLK,
                    dimension._1, dimension._2, partitioner)
                /*rddA.foreach{
                    x =>
                        val (row, col) = x._1
                        val mat = x._2
                        println(s"row: $row, col: $col, Matrix A")
                        println(mat)
                }*/
            }
            if (repartB) {
                rddB = getNewBlocks(other.blocks, other.ROWS_PER_BLK, other.COLS_PER_BLK,
                    dimension._1, dimension._2, partitioner)
                /*rddB.foreach{
                    x =>
                        val (row, col) = x._1
                        val mat = x._2
                        println(s"row: $row, col: $col, Matrix B")
                        println(mat)
                }*/
            }
            // place holder
            //val t2 = System.currentTimeMillis()
            //println("Matrix addition takes: " + (t2-t1)/1000.0 + " sec")
            addSameDim(rddA.partitionBy(partitioner), rddB.partitionBy(partitioner),
                dimension._1, dimension._2)
        }
    }

    // rddA and rddB already partitioned in the same way
    private def addSameDim(rddA: RDD[MatrixBlk], rddB: RDD[MatrixBlk],
                            RPB: Int, CPB: Int): BlockPartitionMatrix = {
        val rdd = rddA.zipPartitions(rddB, preservesPartitioning = true) { (iter1, iter2) =>
            val buf = new TrieMap[(Int, Int), MLMatrix]()
            for (a <- iter1) {
                if (a != null) {
                    val idx = a._1
                    if (!buf.contains(idx)) buf.putIfAbsent(idx, a._2)
                    else {
                        val old = buf.get(idx).get
                        buf.put(idx, LocalMatrix.add(old, a._2))
                    }
                }
            }
            for (b <- iter2) {
                if (b != null) {
                    val idx = b._1
                    if (!buf.contains(idx)) buf.putIfAbsent(idx, b._2)
                    else {
                        val old = buf.get(idx).get
                        buf.put(idx, LocalMatrix.add(old, b._2))
                    }
                }
            }
            buf.iterator
        }
        new BlockPartitionMatrix(rdd, RPB, CPB, nRows(), nCols())
        /*val addBlks = rddA.cogroup(rddB, genBlockPartitioner())
          .map {
            case ((rowIdx, colIdx), (a, b)) =>
                if (a.size > 1 || b.size > 1) {
                    throw new SparkException("There are multiple MatrixBlocks with indices: " +
                      s"($rowIdx, $colIdx). Please remove the duplicate and try again.")
                }
                if (a.isEmpty) {
                    new MatrixBlk((rowIdx, colIdx), b.head)
                }
                else if (b.isEmpty) {
                    new MatrixBlk((rowIdx, colIdx), a.head)
                }
                else {
                    new MatrixBlk((rowIdx, colIdx), LocalMatrix.add(a.head, b.head))
                }
        }
        new BlockPartitionMatrix(addBlks, RPB, CPB, nRows(), nCols()) */
    }

    private def getNewBlocks(rdd: RDD[MatrixBlk],
                             curRPB: Int, curCPB: Int,
                             targetRPB: Int, targetCPB: Int,
                             partitioner: Partitioner): RDD[MatrixBlk] = {
        val rddNew = rePartition(rdd, curRPB, curCPB, targetRPB, targetCPB)
        rddNew.groupByKey(genBlockPartitioner()).map {
            case ((rowIdx, colIdx), iter) =>
                val rowStart = rowIdx * targetRPB
                val rowEnd = math.min((rowIdx + 1) * targetRPB - 1, nRows() - 1)
                val colStart = colIdx * targetCPB
                val colEnd = math.min((colIdx + 1) * targetCPB - 1, nCols() - 1)
                val (m, n) = (rowEnd.toInt - rowStart + 1, colEnd.toInt - colStart + 1)  // current blk size
                val values = Array.fill(m * n)(0.0)
                val (rowOffset, colOffset) = (rowIdx * targetRPB, colIdx * targetCPB)
                for (elem <- iter) {
                    var arr = elem._5
                    var rowSize = elem._2 - elem._1 + 1
                    for (j <- elem._3 to elem._4; i <- elem._1 to elem._2) {
                        var idx = (j - elem._3) * rowSize + (i - elem._1)
                        // assign arr(idx) to a proper position
                        var (ridx, cidx) = (i - rowOffset, j - colOffset)
                        values(cidx.toInt * m + ridx.toInt) = arr(idx.toInt)
                    }
                }
                // 10% or more 0 elements, use sparse matrix format (according to EDBT'15 paper)
                if (values.count(entry => entry > 0.0) > 0.1 * values.length ) {
                    ((rowIdx, colIdx), new DenseMatrix(m, n, values))
                }
                else {
                    ((rowIdx, colIdx), new DenseMatrix(m, n, values).toSparse)
                }

        }.partitionBy(partitioner)

    }
    // RPB -- #rows_per_blk, CPB -- #cols_per_blk
    private def rePartition(rdd: RDD[MatrixBlk],
                            curRPB: Int, curCPB: Int, targetRPB: Int,
                            targetCPB: Int): RDD[((Int, Int), (Long, Long, Long, Long, Array[Double]))] = {
        rdd.map { case ((rowIdx, colIdx), mat) =>
            val rowStart: Long = rowIdx * curRPB
            val rowEnd: Long = math.min((rowIdx + 1) * curRPB - 1, nRows() - 1)
            val colStart: Long = colIdx * curCPB
            val colEnd: Long = math.min((colIdx + 1) * curCPB - 1, nCols() - 1)
            val (x1, x2) = ((rowStart / targetRPB).toInt, (rowEnd / targetRPB).toInt)
            val (y1, y2) = ((colStart / targetCPB).toInt, (colEnd / targetCPB).toInt)
            val res = ArrayBuffer[((Int, Int), (Long, Long, Long, Long, Array[Double]))]()
            for (r <- x1 to x2; c <- y1 to y2) {
                // (r, c) entry for the target partition scheme
                val rowStartNew: Long = r * targetRPB
                val rowEndNew: Long = math.min((r + 1) * targetRPB - 1, nRows() - 1)
                val colStartNew: Long = c * targetCPB
                val colEndNew: Long = math.min((c + 1) * targetCPB - 1, nCols() - 1)
                val rowRange = findIntersect(rowStart, rowEnd, rowStartNew, rowEndNew)
                val colRange = findIntersect(colStart, colEnd, colStartNew, colEndNew)
                val (rowOffset, colOffset) = (rowIdx * curRPB, colIdx * curCPB)
                val values = ArrayBuffer[Double]()
                for (j <- colRange; i <- rowRange) {
                    values += mat((i - rowOffset).toInt, (j - colOffset).toInt)
                }
                val elem = (rowRange(0), rowRange(rowRange.length - 1),
                  colRange(0), colRange(colRange.length - 1), values.toArray)
                val entry = ((r, c), elem)
                res += entry
            }
            res.toArray
        }.flatMap(x => x)
    }

    private def findIntersect(s1: Long, e1: Long, s2: Long, e2: Long): Array[Long] = {
        val tmp = ArrayBuffer[Long]()
        var (x, y) = (s1, s2)
        while (x <= e1 && y <= e2) {
            if (x == y) {
                tmp += x
                x += 1
                y += 1
            }
            else if (x < y) {
                x += 1
            }
            else {
                y += 1
            }
        }
        tmp.toArray
    }

    def ^(p: Double): BlockPartitionMatrix = {
        val RDD = blocks.map { case (idx, mat) =>
            (idx, LocalMatrix.matrixPow(mat, p))
        }
        new BlockPartitionMatrix(RDD, ROWS_PER_BLK, COLS_PER_BLK, nRows(), nCols())
    }

    // One reason to use `%*%` as matrix multiplication operator is because our
    // system also supports matrix element-wise multiplications.
    def %*%(other: BlockPartitionMatrix): BlockPartitionMatrix = {
        // check for special case
        // i.e., outer-product
        val memory = nRows() * other.nCols() * 8 / (1024 * 1024 * 1024) * 1.0
        if (memory > 10) println(s"Caution: matrix multiplication result size = $memory GB")
        if (COL_BLK_NUM == 1 && other.ROW_BLK_NUM == 1) {
            val nblks1 = nRows() / ROWS_PER_BLK
            val nblks2 = other.nCols() / other.COLS_PER_BLK
            if (nblks1 <= nblks2) {
                multiplyOuterProductDupLeft(other)
            }
            else {
                multiplyOuterProductDupRight(other)
            }
        }
        else {
            multiply(other)
        }
    }

    def multiplyOuterProductDupLeft(other: BlockPartitionMatrix): BlockPartitionMatrix = {
        require(nCols() == other.nRows(), s"#cols of A should be equal to #rows of B, but found " +
          s"A.numCols = ${nCols()}, B.numRows = ${other.nRows()}")
        // For eQTL use-cases, size(A) < size(B), should broadcast A
        val numPartitions = other.blocks.partitions.length
        println(s"B.numParts = $numPartitions")
        other.partitionBy(new ColumnPartitioner(numPartitions))
        // broadcast the smaller matrix to each partition of the larger matrix
        /*val num = blocks.count()
        println(s"this.blocks.number = $num")
        val numOther = other.blocks.count()
        println(s"other.blocks.number = $numOther")*/
        val dupRDD = duplicateCrossPartitions(blocks, numPartitions)

        val RDD = dupRDD.zipPartitions(other.blocks, preservesPartitioning = true) { (iter1, iter2) =>
            val dup = iter1.next()._2
            for {
                x2 <- iter2
                x1 <- dup
            } yield ((x1._1._1, x2._1._2), LocalMatrix.matrixMultiplication(x1._2, x2._2))
        }
        //println(RDD.count())
        new BlockPartitionMatrix(RDD, ROWS_PER_BLK, other.COLS_PER_BLK, nRows(), other.nCols())
    }

    def multiplyOuterProductDupRight(other: BlockPartitionMatrix): BlockPartitionMatrix = {
        require(nCols() == other.nRows(), s"#cols of A should be equal to #rows of B, but found " +
          s"A.numCols = ${nCols()}, B.numRows = ${other.nRows()}")
        val numPartitions = blocks.partitions.length
        println(s"A.numParts = $numPartitions")
        this.partitionBy(new RowPartitioner(numPartitions))
        val dupRDD = duplicateCrossPartitions(other.blocks, numPartitions)
        val RDD = blocks.zipPartitions(dupRDD, preservesPartitioning = true) { (iter1, iter2) =>
            val dup = iter2.next()._2
            for {
                x1 <- iter1
                x2 <- dup
            } yield ((x1._1._1, x2._1._2), LocalMatrix.matrixMultiplication(x1._2, x2._2))
        }
        new BlockPartitionMatrix(RDD, ROWS_PER_BLK, other.COLS_PER_BLK, nRows(), other.nCols())
    }

    // currently the repartitioning of A * B will perform on matrix B
    // if blocks of B do not conform with blocks of A, need to find an optimal strategy
    // NOTE: the generic multiplication method works best for general matrices, such that both are
    //       square matrices of similar dimensions. However, for the degenerated case,
    def multiply(other: BlockPartitionMatrix): BlockPartitionMatrix = {
        val t1 = System.currentTimeMillis()
        require(nCols() == other.nRows(), s"#cols of A should be equal to #rows of B, but found " +
            s"A.numCols = ${nCols()}, B.numRows = ${other.nRows()}")
        var rddB = other.blocks
        //var useOtherMatrix: Boolean = false
        if (COLS_PER_BLK != other.ROWS_PER_BLK) {
            logWarning(s"Repartition Matrix B since A.col_per_blk = $COLS_PER_BLK " +
                s"and B.row_per_blk = ${other.ROWS_PER_BLK}")
            rddB = getNewBlocks(other.blocks, other.ROWS_PER_BLK, other.COLS_PER_BLK,
                COLS_PER_BLK, COLS_PER_BLK, new RowPartitioner(numPartitions))
            //useOtherMatrix = true
        }
        // other.ROWS_PER_BLK = COLS_PER_BLK and square blk for other
        //val OTHER_COL_BLK_NUM = math.ceil(other.nCols() * 1.0 / COLS_PER_BLK).toInt
        //val otherMatrix = new BlockPartitionMatrix(rddB, COLS_PER_BLK, COLS_PER_BLK, other.nRows(), other.nCols())
        //val resPartitioner = BlockCyclicPartitioner(ROW_BLK_NUM, OTHER_COL_BLK_NUM, numWorkers)

        //val resPartitioner = genBlockPartitioner()//new RowPartitioner(numPartitions)
        if (groupByCached == null) {
            groupByCached = blocks.map{ case ((rowIdx, colIdx), matA) =>
                (colIdx, (rowIdx, matA))
            }.groupByKey().cache()
        }
        val rdd1 = groupByCached
        /*println("Collecting skew info for column partition ...")
        val arr = blocks.map { case ((rowIdx, colIdx), mat) =>
        var count = 0
        mat match {
            case dm: DenseMatrix => count = dm.values.length
            case sp: SparseMatrix => count = sp.values.length
            case _ => count = 0
        }
            (colIdx, count)
        }.reduceByKey(_ + _)
        .collect()
        for ((x, c) <- arr) {
            println(s"colId = $x, count = $c")
        }*/

        val rdd2 = rddB.map{ case ((rowIdx, colIdx), matB) =>
            (rowIdx, (colIdx, matB))
        }.groupByKey()
        val rddC = rdd1.join(rdd2)
            .values
            .flatMap{ case (iterA, iterB) =>
                for (blk1 <- iterA; blk2 <- iterB)
                    yield ((blk1._1, blk2._1), LocalMatrix.matrixMultiplication(blk1._2, blk2._2))
                /*val product = mutable.ArrayBuffer[((Int, Int), MLMatrix)]()
                for (blk1 <- iterA; blk2 <- iterB) {
                    val idx = (blk1._1, blk2._1)
                    val c = LocalMatrix.matrixMultiplication(blk1._2, blk2._2)
                    //product.append((idx, LocalMatrix.toBreeze(c)))
                    product.append((idx, c))
                }
                product*/
            }.reduceByKey(LocalMatrix.add(_, _))


        /*  .combineByKey(
            (x: BM[Double]) => x,
            (acc: BM[Double], x) => acc + x,
            (acc1: BM[Double], acc2: BM[Double]) => acc1 + acc2,
            resPartitioner, true, null
        ).mapValues(x => LocalMatrix.fromBreeze(x))*/
        val t2 = System.currentTimeMillis()
        println("Matrix multiplication takes: " + (t2-t1)/1000.0 + " sec")
        new BlockPartitionMatrix(rddC, ROWS_PER_BLK, COLS_PER_BLK, nRows(), other.nCols())
    }

    // A %*% B, suppose matrix B is small enough that B can be duplicated and sent
    // to each partition of A for multiplication. This is especially useful for mat-vec operation.
    // It seems that the duplicate method is not efficient enough as supposed to.
    def multiplyDuplicate(other: BlockPartitionMatrix): BlockPartitionMatrix = {
        require(nCols() == other.nRows(), s"#cols of A should be equal to #rows of B, but found " +
        s"A.numCols = ${nCols()}, B.numRows = ${other.nRows()}")
        var rddB = other.blocks
        if (COLS_PER_BLK != other.ROWS_PER_BLK) {
            logWarning(s"Repartition matrix B since A.col_per_blk = $COLS_PER_BLK " +
            s"and B.row_per_blk = ${other.ROWS_PER_BLK}")
            rddB = getNewBlocks(other.blocks, other.ROWS_PER_BLK, other.COLS_PER_BLK,
                COLS_PER_BLK, COLS_PER_BLK, new RowPartitioner(numPartitions))
        }
        if (groupByCached == null) {
            groupByCached = blocks.map{ case ((i, j), mat) =>
                (i, (j, mat))
            }.groupByKey().cache()
        }
        val rdd1 = groupByCached
        val rdd2 = duplicateCrossPartitions(rddB, rdd1.partitions.length)
        val rdd = rdd1.zipPartitions(rdd2, preservesPartitioning = true) { (iter1, iter2) =>
            val dup = iter2.next()._2
            val buffer = new ArrayBuffer[MatrixBlk]()
            for (p <- iter1) {
                val i = p._1
                for (x <- p._2) {
                    var tmp: MLMatrix = null
                    for (y <- dup) {
                        if (x._1 == y._1._1) {
                            val mul = LocalMatrix.matrixMultiplication(x._2, y._2)
                            if (tmp == null) tmp = mul
                            else {
                                tmp = LocalMatrix.add(tmp, mul)
                            }
                        }
                    }
                    buffer.append(((i, 0), tmp))
                }
            }
            buffer.iterator
        }
        new BlockPartitionMatrix(rdd, ROWS_PER_BLK, COLS_PER_BLK, nRows(), other.nCols())
    }

    // just for comparison purpose, not used in the multiplication code
    def multiplyDMAC(other: BlockPartitionMatrix): BlockPartitionMatrix = {
        require(nCols() == other.nRows(), s"#cols of A should be equal to #rows of B, but found " +
            s"A.numCols = ${nCols()}, B.numRows = ${other.nRows()}")
        var rddB = other.blocks
        //var useOtherMatrix: Boolean = false
        if (COLS_PER_BLK != other.ROWS_PER_BLK) {
        logWarning(s"Repartition Matrix B since A.col_per_blk = $COLS_PER_BLK and B.row_per_blk = ${other.ROWS_PER_BLK}")
            rddB = getNewBlocks(other.blocks, other.ROWS_PER_BLK, other.COLS_PER_BLK,
                COLS_PER_BLK, COLS_PER_BLK, new RowPartitioner(numPartitions))
            //useOtherMatrix = true
        }
        // other.ROWS_PER_BLK = COLS_PER_BLK and square blk for other
        val OTHER_COL_BLK_NUM = math.ceil(other.nCols() * 1.0 / COLS_PER_BLK).toInt
        //val otherMatrix = new BlockPartitionMatrix(rddB, COLS_PER_BLK, COLS_PER_BLK, other.nRows(), other.nCols())
        //val resPartitioner = BlockCyclicPartitioner(ROW_BLK_NUM, OTHER_COL_BLK_NUM, math.max(blocks.partitions.length, rddB.partitions.length))

        val nodes = 8

        val aggregator = new Aggregator[(Int, Int), (MLMatrix, MLMatrix),
        ArrayBuffer[(MLMatrix, MLMatrix)]](createCombiner, mergeValue, mergeCombiner)

        val rdd1 = blocks.map{ case ((rowIdx, colIdx), matA) =>
            (colIdx % nodes, (colIdx, rowIdx, matA))
        }.groupByKey()
        val rdd2 = rddB.map{ case ((rowIdx, colIdx), matB) =>
            (rowIdx % nodes, (rowIdx, colIdx, matB))
        }.groupByKey()

        val rddC = rdd1.join(rdd2)
                .values.flatMap{ case (buf1, buf2) =>
                    val cross = new ArrayBuffer[((Int, Int), (MLMatrix, MLMatrix))]()
                    for (i <- buf1)
                        for (j <- buf2) {
                            if (i._1 == j._1)
                            cross.append(((i._2, j._2), (i._3, j._3)))
                        }
                    cross
                }.mapPartitionsWithContext((context, iter) => {
                    new InterruptibleIterator(context, aggregator.combineValuesByKey(iter, context))
                }, true).map { case (index, buf) =>
                    var re_block: MLMatrix = null
                    for ((blk1, blk2) <- buf) {
                        val mul = LocalMatrix.matrixMultiplication(blk1, blk2)
                        re_block match {
                            case null => re_block = mul
                            case _ => re_block = LocalMatrix.add(mul, re_block)
                        }
                    }
                    (index, re_block)
                }.reduceByKey(LocalMatrix.add(_, _))
        new BlockPartitionMatrix(rddC, ROWS_PER_BLK, COLS_PER_BLK, nRows(), other.nCols())
    }

def createCombiner (v: (MLMatrix, MLMatrix)) = ArrayBuffer(v)

def mergeValue(cur: ArrayBuffer[(MLMatrix, MLMatrix)], v: (MLMatrix, MLMatrix)) = cur += v

def mergeCombiner(c1 : ArrayBuffer[(MLMatrix, MLMatrix)], c2 : ArrayBuffer[(MLMatrix, MLMatrix)]) = c1 ++ c2


/*
 * Compute top-k largest elements from the block partitioned matrix.
 */
    def topK(k: Int): Array[((Long, Long), Double)] = {
        val res = blocks.map { case ((rowIdx, colIdx), matrix) =>
            val pq = new mutable.PriorityQueue[((Int, Int), Double)]()(Ordering.by(orderByValueInt))
            for (i <- 0 until matrix.numRows; j <- 0 until matrix.numCols) {
                if (pq.size < k) {
                    pq.enqueue(((i, j), matrix(i, j)))
                }
                else {
                    pq.enqueue(((i, j), matrix(i, j)))
                    pq.dequeue()
                }
            }
            ((rowIdx, colIdx), pq.toArray)
        }.collect()
        val q = new mutable.PriorityQueue[((Long, Long), Double)]()(Ordering.by(orderByValueDouble))
        for (((blk_row, blk_col), arr) <- res) {
            val offset = (blk_row * ROWS_PER_BLK.toLong, blk_col * COLS_PER_BLK.toLong)
            for (((i, j), v) <- arr) {
                if (q.size < k) {
                    q.enqueue(((offset._1 + i, offset._2 + j), v))
                }
                else {
                    q.enqueue(((offset._1 + i, offset._2 + j), v))
                    q.dequeue()
                }
            }
        }
        q.toArray
    }

    private def orderByValueInt(t: ((Int, Int), Double)) = {
        - t._2
    }

    private def orderByValueDouble(t: ((Long, Long), Double)) = {
        - t._2
    }

    // duplicate the matrix for parNum copies and distribute them over the cluster
    private def duplicateCrossPartitions(rdd: RDD[MatrixBlk], parNum: Int): RDD[(Int, Iterable[MatrixBlk])] = {
        rdd.flatMap{ case ((row, col), mat) =>
            /*val arr = new Array[(Int, MatrixBlk)](parNum)
            for (i <- 0 until parNum) {
                arr(i) = (i, ((row, col), mat))
            }
            arr*/
            for (i <- 0 until parNum)
                yield (i, ((row, col), mat))
        }.groupByKey(new IndexPartitioner(parNum))
        //.mapValues(iter => iter.toIterator)
    }

    def blockMultiplyDup(other: BlockPartitionMatrix): BlockPartitionMatrix = {
        require(nCols() == other.nRows(), s"#cols of A should be equal to #rows of B, but found " +
            s"A.numCols = ${nCols()}, B.numRows = ${other.nRows()}")
        var rddB = other.blocks
        //var useOtherMatrix: Boolean = false
        if (COLS_PER_BLK != other.ROWS_PER_BLK) {
            logWarning(s"Repartition Matrix B since A.col_per_blk = $COLS_PER_BLK and B.row_per_blk = ${other.ROWS_PER_BLK}")
            rddB = getNewBlocks(other.blocks, other.ROWS_PER_BLK, other.COLS_PER_BLK,
            COLS_PER_BLK, COLS_PER_BLK, new RowPartitioner(numPartitions))
            //useOtherMatrix = true
        }

        val otherDup = duplicateCrossPartitions(rddB, blocks.partitions.length)

        val OTHER_COL_BLK_NUM = math.ceil(other.nCols() * 1.0 / COLS_PER_BLK).toInt
        val resultPartitioner = BlockCyclicPartitioner(ROW_BLK_NUM, OTHER_COL_BLK_NUM, numPartitions)

        val partRDD = blocks.mapPartitionsWithIndex { (idx, iter) =>
            Iterator((idx, iter.toIndexedSeq.iterator))
        }
        //println("mapPartitionsWithIndex: " + partRDD.count())
        val prodRDD = partRDD.join(otherDup)
                .flatMap { case (pidx, (iter1, iter2)) =>
                    // aggregate local block matrices on each partition
                    val idxToMat = new TrieMap[(Int, Int), MLMatrix]()
                    val iter2Dup = iter2.toArray
                    for (blk1 <- iter1) {
                        for (blk2 <- iter2Dup) {
                            if (blk1._1._2 == blk2._1._1) {
                                val key = (blk1._1._1, blk2._1._2)
                                if (!idxToMat.contains(key)) {
                                    val prod = LocalMatrix.matrixMultiplication(blk1._2, blk2._2)
                                    idxToMat.putIfAbsent(key, prod)
                                }
                                else {
                                    val prod1 = idxToMat.get(key)
                                    val prod2 = LocalMatrix.add(prod1.get, LocalMatrix.matrixMultiplication(blk1._2, blk2._2))
                                    idxToMat.replace(key, prod2)
                                }
                            }
                        }
                    }
                    idxToMat.iterator
                }.reduceByKey(resultPartitioner, LocalMatrix.add(_, _))
        new BlockPartitionMatrix(prodRDD, ROWS_PER_BLK, COLS_PER_BLK, nRows(), other.nCols())
    }

    def frobenius(): Double = {
        val t = blocks.map { case ((i, j), mat) =>
                val x = LocalMatrix.frobenius(mat)
                x * x
            }.reduce(_ + _)
        math.sqrt(t)
    }

    def sumAlongRow(): BlockPartitionMatrix = { // one partition may contain multiple row blocks!!
        val rdd = blocks.map{ case ((rowIdx, colIdx), mat) =>
            val matrix = mat match {
                case den: DenseMatrix =>
                    val arr = new Array[Double](den.numRows)
                    val m = den.numRows
                    val values = den.values
                    for (i <- 0 until values.length) {
                        arr(i % m) += values(i)
                    }
                    new DenseMatrix(m, 1, arr)
                case sp: SparseMatrix =>
                    val arr = new Array[Double](sp.numRows)
                    val m = sp.numRows
                    val values = sp.toArray
                    for (i <- 0 until values.length) {
                        arr(i % m) += values(i)
                    }
                    new DenseMatrix(m, 1, arr)
                case _ => throw new SparkException("Undefined matrix type in sumAlongRow()")
            }
            (rowIdx, matrix.asInstanceOf[MLMatrix])
        }.reduceByKey(LocalMatrix.add(_, _))
         .map {case (rowIdx, mat) =>
             ((rowIdx, 0), mat)
         }
        new BlockPartitionMatrix(rdd, ROWS_PER_BLK, COLS_PER_BLK, nRows(), 1L)
    }

//TODO: come up with a better sampling method,
//TODO: it is too BAD to collect each block info since we only need a small portion of them
// CHECK this implementation!!!
    def sample(ratio: Double) = {
        val sampleColNum: Int = math.max((ratio * COL_BLK_NUM).toInt, 1)
        val colStep: Int = COL_BLK_NUM / sampleColNum
        val colSet = scala.collection.mutable.SortedSet[Int]()
        for (j <- 0 until sampleColNum)
            colSet += j*colStep
        val colNNZ = blocks.map { case ((rowIdx, colIdx), mat) =>
                if (colSet.contains(colIdx)) {
                    val nnz = mat match {
                        case den: DenseMatrix => den.values.length
                        case sp: SparseMatrix => sp.values.length
                    }
                    (colIdx, nnz)
                }
                else (colIdx, 0)
             }.filter(x => x._2 != 0)
              .reduceByKey(_ + _)
              .collect()
        for (elem <- colNNZ) {
            colBlkMap(elem._1) = elem._2
        }
        val sampleRowNum: Int = math.max((ratio * ROW_BLK_NUM).toInt, 1)
        val rowStep: Int = ROW_BLK_NUM / sampleRowNum
        val rowSet = scala.collection.mutable.SortedSet[Int]()
        for (i <- 0 until sampleRowNum)
            rowSet += i * rowStep
        val rowNNZ = blocks.map { case ((rowIdx, colIdx), mat) =>
                        if (rowSet.contains(rowIdx)) {
                            val nnz = mat match {
                                case den: DenseMatrix => den.values.length
                                case sp: SparseMatrix => sp.values.length
                            }
                            (rowIdx, nnz)
                        }
                        else (rowIdx, 0)
                    }.filter(x => x._2 != 0)
                    .reduceByKey(_ + _)
                    .collect()
        for (elem <- rowNNZ) {
            rowBlkMap(elem._1) = elem._2
        }
    }

    // this method implements the rank-1 update for an existing matrix
    // without explicitly materializing the matrix
    // A = A + xx^T
    def rankOneUpdate(vec: BlockPartitionMatrix): BlockPartitionMatrix = {
        require(vec.nCols() == 1, "Vector column size is not 1")
        require(this.nrows == vec.nRows() && this.ncols ==  vec.nRows(),
            s"Dimension not match for matrix addition, A.nrows = ${this.nrows}, " +
            s"A.ncols = ${this.ncols}, B.nrows = ${vec.nRows()}, B.ncols = ${vec.nRows()}")
        val numPartitions = blocks.partitions.length
        val dupRDD = duplicateCrossPartitions(vec.blocks, numPartitions)
        val RDD = blocks.zipPartitions(dupRDD, preservesPartitioning = true) { (iter1, iter2) =>
            val dup = iter2.next()._2
            for {
                x1 <- iter1
                x2 <- dup
                x3 <- dup
                if (x1._1._1 == x2._1._1 && x1._1._2 == x3._1._1)
            } yield (x1._1, LocalMatrix.rankOneAdd(x1._2, x2._2, x3._2))
        }
        new BlockPartitionMatrix(RDD, ROWS_PER_BLK, COLS_PER_BLK, nRows(), nCols())
    }

    // vec() function to stack the elements of a matrix in the column fashion
    def vec(): BlockPartitionMatrix = {
        val blkSize = ROWS_PER_BLK
        val RDD = blocks.flatMap{ case ((i, j), mat) =>
            val arr = mat.toArray
            val numLocalRows = mat.numRows
            val numLocalCols = mat.numCols
            val buffer = ArrayBuffer[((Int, Int), MLMatrix)]()
            for (t <- 0 until numLocalCols) {
                val key = (j * ROW_BLK_NUM * blkSize + t * ROW_BLK_NUM + i, 0)
                val vecArray = new Array[Double](numLocalRows)
                for (i <- 0 until numLocalRows) {
                    vecArray(i) = arr(t * numLocalCols + i)
                }
                buffer.append((key, new DenseMatrix(vecArray.length, 1, vecArray)))
            }
            buffer
        }
        new BlockPartitionMatrix(RDD, blkSize, blkSize, nRows()*nCols(), 1)
    }
}

object BlockPartitionMatrix {
    // TODO: finish some helper factory methods
    def zeros(): BlockPartitionMatrix = {
        new BlockPartitionMatrix(null, 0, 0, 0, 0)
    }
    def createFromCoordinateEntries(entries: RDD[Entry],
                                    ROWS_PER_BLK: Int,
                                    COLS_PER_BLK: Int,
                                    ROW_NUM: Long = 0,
                                    COL_NUM: Long = 0): BlockPartitionMatrix = {
        require(ROWS_PER_BLK > 0, s"ROWS_PER_BLK needs to be greater than 0. " +
          s"But found ROWS_PER_BLK = $ROWS_PER_BLK")
        require(COLS_PER_BLK > 0, s"COLS_PER_BLK needs to be greater than 0. " +
          s"But found COLS_PER_BLK = $COLS_PER_BLK")
        var colSize = 0L
        if (COL_NUM > 0) {
            colSize = COL_NUM
        }
        else {
            colSize = entries.map(x => x.col).max() + 1
        }
        var rowSize = 0L
        if (ROW_NUM > 0) {
            rowSize = ROW_NUM
        }
        else {
            rowSize = entries.map(x => x.row).max() + 1
        }
        /*var colSize = entries.map(x => x.col).max() + 1
        if (COL_NUM > 0 && colSize > COL_NUM) {
            println(s"Computing colSize is greater than COL_NUM, colSize = $colSize, COL_NUM = $COL_NUM")
        }
        if (COL_NUM > colSize) colSize = COL_NUM
        var rowSize = entries.map(x => x.row).max() + 1
        if (ROW_NUM > 0 && rowSize > ROW_NUM) {
            println(s"Computing rowSize is greater than ROW_NUM, rowSize = $rowSize, ROW_NUM = $ROW_NUM")
        }
        if (ROW_NUM > rowSize) rowSize = ROW_NUM */
        val ROW_BLK_NUM = math.ceil(rowSize * 1.0 / ROWS_PER_BLK).toInt
        val COL_BLK_NUM = math.ceil(colSize * 1.0 / COLS_PER_BLK).toInt
        //val partitioner = BlockCyclicPartitioner(ROW_BLK_NUM, COL_BLK_NUM, entries.partitions.length)
        //val partitioner = new ColumnPartitioner(8)
        val blocks: RDD[((Int, Int), MLMatrix)] = entries.map { entry =>
            val blkRowIdx = (entry.row / ROWS_PER_BLK).toInt
            val blkColIdx = (entry.col / COLS_PER_BLK).toInt
            val rowId = entry.row % ROWS_PER_BLK
            val colId = entry.col % COLS_PER_BLK
            ((blkRowIdx, blkColIdx), (rowId.toInt, colId.toInt, entry.value))
        }.groupByKey().map { case ((blkRowIdx, blkColIdx), entry) =>
            val effRows = math.min(rowSize - blkRowIdx.toLong * ROWS_PER_BLK, ROWS_PER_BLK).toInt
            val effCols = math.min(colSize - blkColIdx.toLong * COLS_PER_BLK, COLS_PER_BLK).toInt
            ((blkRowIdx, blkColIdx), SparseMatrix.fromCOO(effRows, effCols, entry))
        }
        new BlockPartitionMatrix(blocks, ROWS_PER_BLK, COLS_PER_BLK, rowSize, colSize)
    }

    def PageRankMatrixFromCoordinateEntries(entries: RDD[Entry],
                                            ROWS_PER_BLK: Int,
                                            COLS_PER_BLK: Int): BlockPartitionMatrix = {
        require(ROWS_PER_BLK > 0, s"ROWS_PER_BLK needs to be greater than 0. " +
          s"But found ROWS_PER_BLK = $ROWS_PER_BLK")
        require(COLS_PER_BLK > 0, s"COLS_PER_BLK needs to be greater than 0. " +
          s"But found COLS_PER_BLK = $COLS_PER_BLK")
        val rowSize = entries.map(x => x.row).max() + 1
        val colSize = entries.map(x => x.col).max() + 1
        val size = math.max(rowSize, colSize)   // make sure the generating matrix is a square matrix
        val wRdd = entries.map(entry => (entry.row, entry))
              .groupByKey().map { x =>
                (x._1, 1.0 / x._2.size)
            }
        val prEntries = entries.map { entry =>
            (entry.row, entry)
        }.join(wRdd)
          .map { record =>
              val rid = record._2._1.col
              val cid = record._2._1.row
              val v = record._2._1.value * record._2._2
              Entry(rid, cid, v)
          }
        createFromCoordinateEntries(prEntries, ROWS_PER_BLK, COLS_PER_BLK, size, size)
    }

    def onesMatrixList(nrows: Long, ncols: Long, ROWS_PER_BLK: Int, COLS_PER_BLK: Int): List[((Int, Int), MLMatrix)] = {
        val ROW_BLK_NUM = math.ceil(nrows * 1.0 / ROWS_PER_BLK).toInt
        val COL_BLK_NUM = math.ceil(ncols * 1.0 / COLS_PER_BLK).toInt
        var res = scala.collection.mutable.LinkedList[((Int, Int), MLMatrix)]()
        for (i <- 0 until ROW_BLK_NUM; j <- 0 until COL_BLK_NUM) {
            val rowSize = math.min(ROWS_PER_BLK, nrows - i * ROWS_PER_BLK).toInt
            val colSize = math.min(COLS_PER_BLK, ncols - j * COLS_PER_BLK).toInt
            res = res :+ ((i, j), DenseMatrix.ones(rowSize, colSize))
        }
        res.toList
    }
    // estimate a proper block size
    def estimateBlockSize(rdd: RDD[Entry]): Int = {
        val (nrows, ncols) = rdd.map { x =>
            (x.row+1, x.col+1)
        }.reduce { (x0, x1) =>
            (math.max(x0._1, x1._1), math.max(x0._2, x1._2))
        }
        println("(nrows, ncols) = " + s"($nrows, $ncols)")
        // get system parameters
        val numWorkers = rdd.context.getExecutorStorageStatus.length - 1
        println(s"numWorkers = $numWorkers")
        val coresPerWorker = 8
        println(s"coresPerWorker = $coresPerWorker")
        // make each core to process 4 blocks
        var blkSize = math.sqrt(nrows * ncols / (numWorkers * coresPerWorker * 4)).toInt
        blkSize = blkSize - (blkSize % 2000)
        if (blkSize == 0) {
            2000
        }
        else {
            blkSize
        }
    }

    def estimateBlockSizeWithDim(nrows: Long, ncols: Long): Int = {
        // for Hathi default setting
        val numWorkers = 10
        println(s"numWorkers = $numWorkers")
        val coresPerWorker = 8
        println(s"coresPerWorker = $coresPerWorker")
        // make each core to process 4 blocks
        var blkSize = math.sqrt(nrows * ncols / (numWorkers * coresPerWorker * 4)).toInt
        blkSize = blkSize - (blkSize % 2000)
        if (blkSize == 0) {
            2000
        }
        else {
            blkSize
        }
    }

    def createVectorE(blkMat: BlockPartitionMatrix): BlockPartitionMatrix = {
        val RDD = blkMat.blocks.filter {
            case ((i, j), mat) => i == 0
        }.map { case ((i, j), mat) =>
            val arr = new Array[Double](mat.numCols)
            for (x <- 0 until arr.length) {
                arr(x) = 1.0
            }
            val matrix: MLMatrix = new DenseMatrix(1, mat.numCols, arr)
            ((i, j), matrix)
        }
        new BlockPartitionMatrix(RDD, blkMat.ROWS_PER_BLK, blkMat.COLS_PER_BLK, 1, blkMat.nCols()).t
    }

    private def defaultRead(line: String): Array[Double] = {
        val elems = line.split("\t")
        val res = new Array[Double](elems.length)
        for (i <- 0 until res.length) {
            res(i) = elems(i).toDouble
        }
        res
    }
    // need RDD source and proper size of the blocks
    def createDenseBlockMatrix(sc: SparkContext,
                               name: String,
                               ROWS_PER_BLOCK: Int,
                               COLS_PER_BLOCK: Int,
                               nrows: Long,
                               ncols: Long,
                               npart: Int = 8,  // number of partitions from Hadoop
                               procLine: String => Array[Double] = defaultRead): BlockPartitionMatrix = {
        val lines = sc.textFile(name, npart)
        val colNum = lines.map{ line =>
            line.trim().split("\t").length
        }.first()
        require(ncols == colNum, s"Error creating dense block matrices, meta data ncols = $ncols, " +
        s"actual colNum = $colNum")
        val RDD0 = lines.map { line =>
            // comment line or title line
            if (line.contains("#") || line.contains("Sample") || line.contains("HG") || line.contains("NA")) {
                (-1, (-1.toLong, line.toString))
            }
            else {
                // here consider tab separated elements in a line
                val rowId = line.trim().split("\t")(0).toLong - 1
                ((rowId / ROWS_PER_BLOCK).toInt, (rowId.toLong, line.trim().substring((rowId+1).toString.length + 1)))
            }
        }.filter(x => x._1 >= 0)
        val rowNum = RDD0.count()
        require(nrows == rowNum, s"Error creating dense block matrices, meta data nrows = $nrows, " +
        s"actual rowNum = $rowNum")
         val RDD = RDD0.groupByKey()
         .flatMap { case (rowBlkId, iter) =>
             // each row should have math.ceil(colNum / COLS_PER_BLOCK)
             val numColBlks = math.ceil(colNum * 1.0/ COLS_PER_BLOCK).toInt
             // each one of the 2d array may have different lengths according to the key
             val arrs = Array.ofDim[Array[Double]](numColBlks)
             var currRow = 0
             if (rowBlkId == nrows / ROWS_PER_BLOCK) {
                 currRow = (nrows - ROWS_PER_BLOCK * rowBlkId).toInt
             }
             else {
                 currRow = ROWS_PER_BLOCK
             }
             for (j <- 0 until arrs.length) {
                 if (j == ncols / COLS_PER_BLOCK) {
                     arrs(j) = Array.ofDim[Double]((currRow * (ncols - ncols / COLS_PER_BLOCK * COLS_PER_BLOCK)).toInt)
                 }
                 else {
                     arrs(j) = Array.ofDim[Double](currRow * COLS_PER_BLOCK)
                 }
             }
             for (row <- iter) {
                 val rowId = row._1
                 val values = procLine(row._2)
                 for (j <- 0 until values.length) {
                    val colBlkId = j / COLS_PER_BLOCK
                    val localRowId = rowId - rowBlkId * ROWS_PER_BLOCK
                    val localColId = j - colBlkId * COLS_PER_BLOCK
                    val idx = currRow * localColId + localRowId
                    arrs(colBlkId)(idx.toInt) = values(j)
                 }
             }
             val buffer = ArrayBuffer[((Int, Int), MLMatrix)]()
             for (j <- 0 until arrs.length) {
                 if (j == ncols / COLS_PER_BLOCK) {
                     buffer.append(((rowBlkId.toInt, j),
                       new DenseMatrix(currRow, (ncols - ncols / COLS_PER_BLOCK * COLS_PER_BLOCK).toInt, arrs(j))))
                 }
                 else {
                     buffer.append(((rowBlkId.toInt, j),
                       new DenseMatrix(currRow, COLS_PER_BLOCK, arrs(j))))
                 }
             }
             buffer
         }
        new BlockPartitionMatrix(RDD, ROWS_PER_BLOCK, COLS_PER_BLOCK, nrows, ncols)
    }

    // create a uniform random matrix according to the given dimensions
    def randMatrix(sc: SparkContext,
                   nrows: Long,
                   ncols: Long,
                   blkSize: Int,
                   min: Double = 0.0,
                   max: Double = 1.0): BlockPartitionMatrix = {
        val rowBlkCnt = math.ceil(nrows * 1.0 / blkSize).toInt
        val colBlkCnt = math.ceil(ncols * 1.0 / blkSize).toInt
        val idx = for (i <- 0 until rowBlkCnt; j <- 0 until colBlkCnt) yield (i, j)
        val blkId = sc.parallelize(idx)
        val rdd = blkId.map { case (i, j) =>
            val curRow = math.min(blkSize, nrows - i * blkSize).toInt
            val curCol = math.min(blkSize, ncols - j * blkSize).toInt
            ((i, j), LocalMatrix.rand(curRow, curCol, min, max))
        }
        new BlockPartitionMatrix(rdd, blkSize, blkSize, nrows, ncols)
    }
}

object TestBlockPartition {
    def main (args: Array[String]) {
        val conf = new SparkConf()
          .setMaster("local[4]")
          .setAppName("Test for block partition matrices")
          .set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
          .set("spark.shuffle.consolidateFiles", "true")
          .set("spark.shuffle.compress", "false")
        val sc = new SparkContext(conf)
        // the following test the block matrix addition and multiplication
        val m1 = new DenseMatrix(3,3,Array[Double](1,7,13,2,8,14,3,9,15))
        val m2 = new DenseMatrix(3,3,Array[Double](4,10,16,5,11,17,6,12,18))
        val m3 = new DenseMatrix(3,3,Array[Double](19,25,31,20,26,32,21,27,33))
        val m4 = new DenseMatrix(3,3,Array[Double](22,28,34,23,29,35,24,30,36))
        val n1 = new DenseMatrix(4,4,Array[Double](1,1,1,3,1,1,1,3,1,1,1,3,2,2,2,4))
        val n2 = new DenseMatrix(4,2,Array[Double](2,2,2,4,2,2,2,4))
        val n3 = new DenseMatrix(2,4,Array[Double](3,3,3,3,3,3,4,4))
        val n4 = new DenseMatrix(2,2,Array[Double](4,4,4,4))
        val x1 = new DenseMatrix(3,1,Array[Double](1,2,3))
        val x2 = new DenseMatrix(3,1,Array[Double](4,5,6))
        val y1 = new DenseMatrix(1,3,Array[Double](1,2,3))
        val y2 = new DenseMatrix(1,3,Array[Double](4,5,6))
        val o1 = new DenseMatrix(2,2,Array[Double](1,1,1,1))
        val o2 = new DenseMatrix(2,2,Array[Double](2,2,2,2))
        val o3 = new DenseMatrix(2,2,Array[Double](3,3,3,3))
        val o4 = new DenseMatrix(2,2,Array[Double](4,4,4,4))
        val o5 = new DenseMatrix(2,2,Array[Double](5,5,5,5))
        val s1 = new DenseMatrix(2,1,Array[Double](1,2))
        val s2 = new DenseMatrix(2,1,Array[Double](1,2))
        val t1 = new DenseMatrix(2,1,Array[Double](3,4))
        val t2 = new DenseMatrix(2,1,Array[Double](3,4))
        val arr1 = Array[((Int, Int), MLMatrix)](((0,0),m1), ((0,1), m2), ((1,0), m3), ((1,1), m4))
        val arr2 = Array[((Int, Int), MLMatrix)](((0,0),n1), ((0,1), n2), ((1,0), n3), ((1,1), n4))
        val arrx = Array[((Int, Int), MLMatrix)](((0,0),x1), ((1,0),x2))
        val arry = Array[((Int, Int), MLMatrix)](((0,0),y1), ((0,1),y2))
        val arro1 = Array[((Int, Int), MLMatrix)](((0,0),o1))
        val arro2 = Array[((Int, Int), MLMatrix)](((0,0),o2), ((0,1),o3), ((0,2),o4), ((0,3),o5))
        val vec1 = Array[((Int, Int), MLMatrix)](((0,0),s1), ((1,0),s2))
        val vec2 = Array[((Int, Int), MLMatrix)](((0,0),t1), ((1,0),t2))
        val rdd1 = sc.parallelize(arr1, 2)
        val rdd2 = sc.parallelize(arr2, 2)
        val rddx = sc.parallelize(arrx, 2)
        val rddy = sc.parallelize(arry, 2)
        val rddo1 = sc.parallelize(arro1, 2)
        val rddo2 = sc.parallelize(arro2, 2)
        val rddv1 = sc.parallelize(vec1, 2)
        val rddv2 = sc.parallelize(vec2, 2)
        val mat1 = new BlockPartitionMatrix(rdd1, 3, 3, 6, 6)
        val mat2 = new BlockPartitionMatrix(rdd2, 4, 4, 6, 6)
        val matx = new BlockPartitionMatrix(rddx, 3,3,6,1)
        val maty = new BlockPartitionMatrix(rddy, 3,3,1,6)
        val mato1 = new BlockPartitionMatrix(rddo1, 2, 2, 2, 2)
        val mato2 = new BlockPartitionMatrix(rddo2, 2, 2, 2, 8)
        val v1 = new BlockPartitionMatrix(rddv1, 2, 2, 4, 1)
        val v2 = new BlockPartitionMatrix(rddv2, 2, 2, 4, 1)
        //mat1.partitionByBlockCyclic()
        //println((mat1 + mat2).toLocalMatrix())
        /*  addition
         *  2.0   3.0   4.0   6.0   7.0   8.0
            8.0   9.0   10.0  12.0  13.0  14.0
            14.0  15.0  16.0  18.0  19.0  20.0
            22.0  23.0  24.0  26.0  27.0  28.0
            28.0  29.0  30.0  32.0  33.0  34.0
            34.0  35.0  36.0  38.0  39.0  40.0
         */
        //println((mat1 %*% mat2).sumAlongRow().toLocalMatrix())
        /*   multiplication
             51    51    51    72    72    72
             123   123   123   180   180   180
             195   195   195   288   288   288
             267   267   267   396   396   396
             339   339   339   504   504   504
             411   411   411   612   612   612
         */
        //println((matx %*% maty).toLocalMatrix())
        //println((mato1 %*% mato2).toLocalMatrix())

        /*val denseBlkMat = BlockPartitionMatrix.createDenseBlockMatrix(sc,
            "/Users/yongyangyu/Desktop/krux-master-dev/test/mrna.tab.tmp", 3,6,10,10)
        println(denseBlkMat.toLocalMatrix())
        val zMat = BlockPartitionMatrix.zeros()
        println((zMat + denseBlkMat).toLocalMatrix())*/
        /*val mat = List[(Long, Long)]((0, 0), (0,1), (0,2), (0,3), (0, 4), (0, 5), (1, 0), (1, 2),
            (2, 3), (2, 4), (3,1), (3,2), (3, 4), (4, 5), (5, 4))
        val CooRdd = sc.parallelize(mat, 2).map(x => Entry(x._1, x._2, 1.0))
        var matrix = BlockPartitionMatrix.PageRankMatrixFromCoordinateEntries(CooRdd, 3, 3).cache()
        val vec = BlockPartitionMatrix.onesMatrixList(6, 1, 3, 3)//List[((Int, Int), MLMatrix)](((0, 0), DenseMatrix.ones(3, 1)), ((1, 0), DenseMatrix.ones(3, 1)))
        val vecRdd = sc.parallelize(vec, 2)
        var x = new BlockPartitionMatrix(vecRdd, 3, 3, 6, 1).multiplyScalar(1.0 / 6)
        var v = new BlockPartitionMatrix(vecRdd, 3, 3, 6, 1).multiplyScalar(1.0 / 6)
        val alpha = 0.85
        matrix = (alpha *: matrix).partitionByBlockCyclic().cache()
        v = (1.0 - alpha) *: v
        for (i <- 0 until 10) {
            x = (matrix %*% x) + (v, (3,3))
            //x = matrix.multiply(x).multiplyScalar(alpha).add(v.multiplyScalar(1-alpha), (3,3))
        }
        println(x.toLocalMatrix())*/
        println(mat1.toLocalMatrix())
        println(matx.toLocalMatrix())
        val rank1 = mat1.rankOneUpdate(matx)
        println(rank1.toLocalMatrix())
        println(rank1.vec().toLocalMatrix())
        println((v1 %*% (v2.t)).toLocalMatrix())
        sc.stop()
    }
}