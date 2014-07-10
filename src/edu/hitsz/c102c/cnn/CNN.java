package edu.hitsz.c102c.cnn;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import edu.hitsz.c102c.cnn.Layer.Size;
import edu.hitsz.c102c.data.Dataset;
import edu.hitsz.c102c.data.Dataset.Record;
import edu.hitsz.c102c.util.ConcurenceRunner;
import edu.hitsz.c102c.util.ConcurenceRunner.Task;
import edu.hitsz.c102c.util.Log;
import edu.hitsz.c102c.util.Util;
import edu.hitsz.c102c.util.Util.Operator;

public class CNN {
	private static final double ALPHA = 0.05;
	protected static final double LAMBDA = 0.01;
	// ����ĸ���
	private List<Layer> layers;
	// ����
	private int layerNum;
	// ���й���
	private static ConcurenceRunner runner = new ConcurenceRunner();
	// �������µĴ�С
	private int batchSize;
	// �������������Ծ����ÿһ��Ԫ�س���һ��ֵ
	private Operator divide_batchSize;

	// �������������Ծ����ÿһ��Ԫ�س���alphaֵ
	private Operator multiply_alpha;

	// �������������Ծ����ÿһ��Ԫ�س���1-labmda*alphaֵ
	private Operator multiply_lambda;

	/**
	 * ��ʼ������
	 * 
	 * @param layerBuilder
	 *            �����
	 * @param inputMapSize
	 *            ����map�Ĵ�С
	 * @param classNum
	 *            ���ĸ�����Ҫ�����ݼ������ת��Ϊ0-classNum-1����ֵ
	 */
	public CNN(LayerBuilder layerBuilder, final int batchSize) {
		layers = layerBuilder.mLayers;
		layerNum = layers.size();
		this.batchSize = batchSize;
		setup(batchSize);
		initPerator();
	}

	/**
	 * ��ʼ��������
	 */
	private void initPerator() {
		divide_batchSize = new Operator() {

			@Override
			public double process(double value) {
				return value / batchSize;
			}

		};
		multiply_alpha = new Operator() {

			@Override
			public double process(double value) {

				return value * ALPHA;
			}

		};
		multiply_lambda = new Operator() {

			@Override
			public double process(double value) {

				return value * (1 - LAMBDA * ALPHA);
			}

		};
	}

	/**
	 * ��ѵ������ѵ������
	 * 
	 * @param trainset
	 * @param repeat
	 *            �����Ĵ���
	 */
	public void train(Dataset trainset, int repeat) {
		for (int t = 0; t < repeat; t++) {
			int epochsNum = 1 + trainset.size() / batchSize;// ���ȡһ�Σ�������ȡ��
			for (int i = 0; i < epochsNum; i++) {
				int[] randPerm = Util.randomPerm(trainset.size(), batchSize);
				Layer.prepareForNewBatch();
				for (int index : randPerm) {
					train(trainset.getRecord(index));
					Layer.prepareForNewRecord();
				}
				Layer.prepareForNewBatch();
				// ����һ��batch�����Ȩ��
				updateParas();
			}
			double precision = test(trainset);
			Log.i("precision " + precision);
		}
	}

	/**
	 * ��������
	 * 
	 * @param trainset
	 * @return
	 */
	private double test(Dataset trainset) {
		Iterator<Record> iter = trainset.iter();
		int right = 0;
		while (iter.hasNext()) {
			Record record = iter.next();
			forward(record);
			Layer outputLayer = layers.get(layerNum - 1);
			int mapNum = outputLayer.getOutMapNum();
			double[] target = record.getDoubleEncodeTarget(mapNum);
			double[] out = new double[mapNum];
			for (int m = 0; m < mapNum; m++) {
				double[][] outmap = outputLayer.getMap(m);
				out[m] = outmap[0][0];
			}
			if (isSame(out, target)) {
				right++;
			}
		}
		return 1.0 * right / trainset.size();
	}

	private boolean isSame(double[] output, double[] target) {
		boolean r = true;
		for (int i = 0; i < output.length; i++)
			if (Math.abs(output[i] - target[i]) > 0.5) {
				r = false;
				break;
			}

		return r;
	}

	private void train(Record record) {
		forward(record);
		backPropagation(record);
	}

	/*
	 * ������
	 */
	private void backPropagation(Record record) {
		setOutLayerErrors(record);
		setHiddenLayerErrors();
	}

	/**
	 * ���²���
	 */
	private void updateParas() {
		for (int l = 1; l < layerNum; l++) {
			Layer layer = layers.get(l);
			Layer lastLayer = layers.get(l - 1);
			switch (layer.getType()) {
			case conv:
			case output:
				updateParas(layer, lastLayer);
				break;
			default:
				break;
			}
		}
	}

	/**
	 * ����layer��ľ����ˣ�Ȩ�أ���ƫ��
	 * 
	 * @param layer
	 *            ��ǰ��
	 * @param lastLayer
	 *            ǰһ��
	 */
	private void updateParas(Layer layer, Layer lastLayer) {
		int mapNum = layer.getOutMapNum();
		int lastMapNum = lastLayer.getOutMapNum();
		for (int j = 0; j < mapNum; j++) {
			double[][][][] errors = layer.getErrors();
			double[][] error = Util.sum(errors, j);
			for (int i = 0; i < lastMapNum; i++) {
				double[][] kernel = layer.getKernel(i, j);
				double[][] deltaKernel = Util.convnValid(
						Util.rot180(lastLayer.getMap(i)), error);
				// ����batchSize
				deltaKernel = Util.matrixOp(deltaKernel, divide_batchSize);
				// ���¾�����
				deltaKernel = Util.matrixOp(kernel, deltaKernel,
						multiply_lambda, multiply_alpha, Util.minus);
				layer.setKernel(i, j, deltaKernel);
			}
			// ����ƫ��
			double deltaBias = Util.sum(error) / batchSize;
			double bias = layer.getBias(j) + ALPHA * deltaBias;
			layer.setBias(j, bias);
		}
	}

	/**
	 * �����н�����Ĳв�
	 */
	private void setHiddenLayerErrors() {
		for (int l = layerNum - 2; l > 0; l--) {
			Layer layer = layers.get(l);
			Layer nextLayer = layers.get(l + 1);
			switch (layer.getType()) {
			case samp:
				setSampErrors(layer, nextLayer);
				break;
			case conv:
				setConvErrors(layer, nextLayer);
				break;
			default:// ֻ�в�����;�������Ҫ�����в�����û�вв������Ѿ�������
				break;
			}
		}
	}

	/**
	 * ���ò�����Ĳв�
	 * 
	 * @param layer
	 * @param nextLayer
	 */
	private void setSampErrors(Layer layer, Layer nextLayer) {
		int mapNum = layer.getOutMapNum();
		final int nextMapNum = nextLayer.getOutMapNum();
		for (int i = 0; i < mapNum; i++) {
			double[][] sum = null;// ��ÿһ�������������
			for (int j = 0; j < nextMapNum; j++) {
				double[][] nextError = nextLayer.getError(j);
				double[][] kernel = nextLayer.getKernel(i, j);
				// �Ծ����˽���180����ת��Ȼ�����fullģʽ�µþ���
				if (sum == null)
					sum = Util.convnFull(nextError, Util.rot180(kernel));
				else
					sum = Util.matrixOp(
							Util.convnFull(nextError, Util.rot180(kernel)),
							sum, null, null, Util.plus);
			}
			layer.setMapValue(i, sum);
		}
	}

	/**
	 * ���þ�����Ĳв�
	 * 
	 * @param layer
	 * @param nextLayer
	 */
	private void setConvErrors(final Layer layer, final Layer nextLayer) {
		// ���������һ��Ϊ�����㣬�������map������ͬ����һ��mapֻ����һ���һ��map���ӣ�
		// ���ֻ�轫��һ��Ĳв�kronecker��չ���õ������
		int mapNum = layer.getOutMapNum();
		int cpuNum = ConcurenceRunner.cpuNum;
		cpuNum = cpuNum < mapNum ? cpuNum : 1;// ��cpu�ĸ���Сһ��ʱ��ֻ��һ���߳�
		final CountDownLatch gate = new CountDownLatch(cpuNum);
		int fregLength = (mapNum + cpuNum - 1) / cpuNum;
		for (int cpu = 0; cpu < cpuNum; cpu++) {
			int start = cpu * fregLength;
			int tmp = (cpu + 1) * fregLength;
			int end = tmp <= mapNum ? tmp : mapNum;
			Task task = new Task(start, end) {

				@Override
				public void process(int start, int end) {
					for (int m = start; m < end; m++) {
						Size scale = nextLayer.getScaleSize();
						double[][] nextError = nextLayer.getError(m);
						double[][] map = layer.getMap(m);
						// ������ˣ����Եڶ��������ÿ��Ԫ��value����1-value����
						double[][] outMatrix = Util.matrixOp(map,
								Util.cloneMatrix(map), null, Util.one_value,
								Util.multiply);
						outMatrix = Util.matrixOp(outMatrix,
								Util.kronecker(nextError, scale), null, null,
								Util.multiply);
						layer.setError(m, outMatrix);
					}
					gate.countDown();
				}
			};
			runner.run(task);
		}
		await(gate);

	}

	/**
	 * ���������Ĳв�ֵ,�������񾭵�Ԫ�������٣��ݲ����Ƕ��߳�
	 * 
	 * @param record
	 */
	private void setOutLayerErrors(Record record) {
		Layer outputLayer = layers.get(layerNum - 1);
		int mapNum = outputLayer.getOutMapNum();
		double[] target = record.getDoubleEncodeTarget(mapNum);
		for (int m = 0; m < mapNum; m++) {
			double[][] outmap = outputLayer.getMap(m);
			double output = outmap[0][0];
			double errors = output * (1 - output) * (target[m] - output);
			outputLayer.setError(m, 0, 0, errors);
		}
	}

	/**
	 * ǰ�����һ����¼
	 * 
	 * @param record
	 */
	private void forward(Record record) {
		// ����������map
		setInLayerOutput(record);
		for (int l = 1; l < layers.size(); l++) {
			Layer layer = layers.get(l);
			Layer lastLayer = layers.get(l - 1);
			switch (layer.getType()) {
			case conv:// �������������
				setConvOutput(layer, lastLayer);
				break;
			case samp:// �������������
				setSampOutput(layer, lastLayer);
				break;
			case output:// �������������,�������һ������ľ�����
				setConvOutput(layer, lastLayer);
				break;
			default:
				break;
			}
		}
	}

	/**
	 * ���ݼ�¼ֵ���������������ֵ
	 * 
	 * @param record
	 */
	private void setInLayerOutput(Record record) {
		final Layer inputLayer = layers.get(0);
		final Size mapSize = inputLayer.getMapSize();
		final double[] attr = record.getAttrs();
		if (attr.length != mapSize.x * mapSize.y)
			throw new RuntimeException("���ݼ�¼�Ĵ�С�붨���map��С��һ��!");
		int cpuNum = ConcurenceRunner.cpuNum;
		cpuNum = cpuNum < mapSize.y ? cpuNum : 1;// ��cpu�ĸ���Сһ��ʱ��ֻ��һ���߳�
		final CountDownLatch gate = new CountDownLatch(cpuNum);
		int fregLength = (mapSize.y + cpuNum - 1) / cpuNum;
		for (int cpu = 0; cpu < cpuNum; cpu++) {
			int start = cpu * fregLength;
			int tmp = (cpu + 1) * fregLength;
			int end = tmp <= mapSize.y ? tmp : mapSize.y;
			Task task = new Task(start, end) {

				@Override
				public void process(int start, int end) {
					for (int i = 0; i < mapSize.x; i++) {
						for (int j = start; j < end; j++) {
							// ����¼���Ե�һά����Ū�ɶ�ά����
							double value = attr[mapSize.x * i + j];
							inputLayer.setMapValue(0, i, j, value);
						}
					}
					gate.countDown();
				}
			};
			runner.run(task);
		}
		await(gate);

	}

	/*
	 * ������������ֵ,ÿ���̸߳���һ����map
	 */
	private void setConvOutput(final Layer layer, final Layer lastLayer) {
		int mapNum = layer.getOutMapNum();
		final int lastMapNum = lastLayer.getOutMapNum();
		int cpuNum = ConcurenceRunner.cpuNum;
		cpuNum = cpuNum < mapNum ? cpuNum : 1;// ��cpu�ĸ���Сһ��ʱ��ֻ��һ���߳�
		final CountDownLatch gate = new CountDownLatch(cpuNum);
		int fregLength = (mapNum + cpuNum - 1) / cpuNum;
		for (int cpu = 0; cpu < cpuNum; cpu++) {
			int start = cpu * fregLength;
			int tmp = (cpu + 1) * fregLength;
			int end = tmp <= mapNum ? tmp : mapNum;
			Task task = new Task(start, end) {

				@Override
				public void process(int start, int end) {
					for (int j = start; j < end; j++) {
						double[][] sum = null;// ��ÿһ������map�ľ����������
						for (int i = 0; i < lastMapNum; i++) {
							double[][] lastMap = lastLayer.getMap(i);
							double[][] kernel = layer.getKernel(i, j);
							if (sum == null)
								sum = Util.convnValid(lastMap, kernel);
							else
								sum = Util.matrixOp(
										Util.convnValid(lastMap, kernel), sum,
										null, null, Util.plus);
						}
						final double bias = layer.getBias(j);
						sum = Util.matrixOp(sum, new Operator() {

							@Override
							public double process(double value) {
								return Util.sigmod(value + bias);
							}

						});
						layer.setMapValue(j, sum);
					}
					gate.countDown();
				}
			};
			runner.run(task);
		}
		await(gate);

	}

	/**
	 * ���ò���������ֵ���������ǶԾ�����ľ�ֵ����
	 * 
	 * @param layer
	 * @param lastLayer
	 */
	private void setSampOutput(final Layer layer, final Layer lastLayer) {
		int lastMapNum = lastLayer.getOutMapNum();
		int cpuNum = ConcurenceRunner.cpuNum;
		cpuNum = cpuNum < lastMapNum ? cpuNum : 1;// ��cpu�ĸ���Сһ��ʱ��ֻ��һ���߳�
		final CountDownLatch gate = new CountDownLatch(cpuNum);
		int fregLength = (lastMapNum + cpuNum - 1) / cpuNum;
		for (int cpu = 0; cpu < cpuNum; cpu++) {
			int start = cpu * fregLength;
			int tmp = (cpu + 1) * fregLength;
			int end = tmp <= lastMapNum ? tmp : lastMapNum;
			Task task = new Task(start, end) {

				@Override
				public void process(int start, int end) {
					for (int i = start; i < end; i++) {
						double[][] lastMap = lastLayer.getMap(i);
						Size scaleSize = layer.getScaleSize();
						// ��scaleSize������о�ֵ����
						double[][] sampMatrix = Util.scaleMatrix(lastMap,
								scaleSize);
						layer.setMapValue(i, sampMatrix);
					}
					gate.countDown();
				}
			};
			runner.run(task);
		}
		await(gate);
	}

	/**
	 * ����cnn�����ÿһ��Ĳ���
	 * 
	 * @param batchSize
	 *            * @param classNum
	 * @param inputMapSize
	 */
	public void setup(int batchSize) {
		Layer inputLayer = layers.get(0);
		// ÿһ�㶼��Ҫ��ʼ�����map
		inputLayer.initOutmaps(batchSize);
		for (int i = 1; i < layers.size(); i++) {
			Layer layer = layers.get(i);
			Layer frontLayer = layers.get(i - 1);
			int frontMapNum = frontLayer.getOutMapNum();
			switch (layer.getType()) {
			case input:
				break;
			case conv:
				// ����map�Ĵ�С
				layer.setMapSize(frontLayer.getMapSize().subtract(
						layer.getKernelSize(), 1));
				// ��ʼ�������ˣ�����frontMapNum*outMapNum��������
				layer.initKerkel(frontMapNum);
				// ��ʼ��ƫ�ã�����frontMapNum*outMapNum��ƫ��
				layer.initBias(frontMapNum);
				// batch��ÿ����¼��Ҫ����һ�ݲв�
				layer.initErros(batchSize);
				// ÿһ�㶼��Ҫ��ʼ�����map
				layer.initOutmaps(batchSize);
				break;
			case samp:
				// �������map��������һ����ͬ
				layer.setOutMapNum(frontMapNum);
				// ������map�Ĵ�С����һ��map�Ĵ�С����scale��С
				layer.setMapSize(frontLayer.getMapSize().divide(
						layer.getScaleSize()));
				// batch��ÿ����¼��Ҫ����һ�ݲв�
				layer.initErros(batchSize);
				// ÿһ�㶼��Ҫ��ʼ�����map
				layer.initOutmaps(batchSize);
				break;
			case output:
				// ��ʼ��Ȩ�أ������ˣ�������frontMapNum*outMapNum��1*1������
				layer.initKerkel(frontMapNum);
				// ��ʼ��ƫ�ã�����frontMapNum*outMapNum��ƫ��
				layer.initBias(frontMapNum);
				// batch��ÿ����¼��Ҫ����һ�ݲв�
				layer.initErros(batchSize);
				// ÿһ�㶼��Ҫ��ʼ�����map
				layer.initOutmaps(batchSize);
				break;
			}

		}
	}

	/**
	 * ������ģʽ�������,Ҫ�����ڶ������Ϊ�����������Ϊ������
	 * 
	 * @author jiqunpeng
	 * 
	 *         ����ʱ�䣺2014-7-8 ����4:54:29
	 */
	public static class LayerBuilder {
		private List<Layer> mLayers;

		public LayerBuilder() {
			mLayers = new ArrayList<Layer>();
		}

		public LayerBuilder(Layer layer) {
			this();
			mLayers.add(layer);
		}

		public LayerBuilder addLayer(Layer layer) {
			mLayers.add(layer);
			return this;
		}
	}

	/**
	 * �ȴ�
	 * 
	 * @param gate
	 */

	private static void await(CountDownLatch gate) {
		try {
			gate.await();
		} catch (InterruptedException e) {
			e.printStackTrace();
			throw new RuntimeException(e);
		}
	}
}