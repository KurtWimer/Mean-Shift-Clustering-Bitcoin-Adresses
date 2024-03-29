import org.apache.spark.rdd.RDD
import org.apache.spark
import org.apache.spark.{SparkConf, SparkContext}

//small test String
//val x = sc.parallelize(Array(("a", "input, 12:00:00, 1"),("a", "output, 12:01:01, 2"),("b", "input, 12:00:32, 3")))

object walletClustering{
	def main(args: Array[String]): Unit = {
		//paramater variables
		val threshold = 10.0
		val iterations = args(2).toInt

		//start spark application
		val conf = new SparkConf().setAppName("Wallet Clustering")
   		val sc = new SparkContext(conf)

   		//read data in and generate wallet information
   		//data rdd key = String(adress) values = Arr[Double] [inputTotal, outputTotal, numTransactions]
		var data = sc.textFile("hdfs:///"+args(0),100).map{v => (v.split(", ")(2).substring(2,v.split(", ")(2).length-2), v)}.mapValues(joinPrep _).reduceByKey(joinFunc _)
		//cache for efficiency
		data.cache()
		
		for(i <- 0 to iterations){
			//find neighbors as defined by having a euclidean distance of < threshold
			val neighbors = data.cartesian(data.values).map{distances}.filter(v => v._2._1 < threshold)
			//generate guassian values for each point pairing
			//guassians rdd key = adress value (guassian, pointValues)
			val guassians = neighbors.mapValues(kernelFunc)
			//cache the expensive cartesian operation and clear memory 
			guassians.repartition(100).cache()
			data.unpersist()
			//collect guassians so that each point has a kernel
			val kernels = guassians.mapValues(v => v._1).reduceByKey(kernelReduce)
			//shift the points
			val adjusted = guassians.mapValues(v => v._2.map{_*v._1}).reduceByKey(shiftReduce).join(kernels).mapValues{case (arr, weight) => arr.map{_/weight}}
			data = adjusted
			//cache output and clear memory
			data.cache()
			guassians.unpersist()
		}

		//output cluster data
		data.mapValues{v => v.mkString(", ")}.saveAsTextFile("hdfs:///"+args(2))

		//known wallet adresses for verification purposes		
		val exchangeWallets = sc.textFile("hdfs:///"+args(3)).map{w => w.split(",")(1)}
        	val poolWallets = sc.textFile("hdfs:///"+args(4)).map{w => w.split(",")(1)}
        	val serviceWallets = sc.textFile("hdfs:///"+args(5)).map{w => w.split(",")(1)}
        	val gamblingWallets = sc.textFile("hdfs:///"+args(6)).map{w => w.split(",")(1)}
        	//verification not implemented
	}

	//inputs either "input"/"output", timestamp, address, val
	//returns out key = key, value = inputTotal, ouputTotal, numTransactions
	def joinPrep(value:String): Array[Double] ={
		val val_arr = value.split(", ")
		if(val_arr(0) == "input"){
			return Array(val_arr(3).toDouble, 0, 1)
		}
		else{
			return Array(0, val_arr(3).toDouble, 1)
		}
	}

	//inputs key = key, value = inputTotal, ouputTotal, numTransactions
	//returns out key = key, value = inputTotal, ouputTotal, numTransactions
	def joinFunc(accum:Array[Double], value:Array[Double]): Array[Double] ={
		return accum.zip(value).map{case (x,y) => x+y}
	}

	//input cartestian product of points and point values
	//output key, val = (distance, point = arr[dbl])
	def distances(value:((String, Array[Double]), Array[Double])): (String, (Double, Array[Double]))={
		val source = value._1._2
		val target = value._2
		val dist = scala.math.sqrt(source.zip(target).map{ case (x, y) => scala.math.pow(y-x, 2)}.sum)
		return (value._1._1, (dist, target))
	}

	//input key, values distance array points
	//output key, values gaussian point
	def kernelFunc(value:(Double, Array[Double])): (Double, Array[Double])={
		//gaussian function
		val distance = value._1
		val target = value._2
		val bandwith = 5.0
		val gaussian = 1/scala.math.pow((2*scala.math.Pi)*bandwith, .5) * scala.math.exp(-.5*scala.math.pow(distance,2)/scala.math.pow(bandwith, 2))
		return (gaussian, value._2)
	}

	def kernelReduce(accum:Double, target:Double): Double={
		return accum + target
	}

	def shiftReduce(accum:Array[Double], target:Array[Double]): Array[Double]={
		return accum.zip(target).map{case (x, y) => x+y}
	}

	def notClustered(old:Array[Double], current:Array[Double]): Boolean ={
		for(w <- 0 to 2){
			if(old(w) != current(w)){
			return false
			}
		}
		return true
	}
}
